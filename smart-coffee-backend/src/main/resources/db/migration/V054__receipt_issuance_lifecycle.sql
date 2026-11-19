-- =============================================================================
-- V054: Receipt issuance lifecycle and recovery (Receipt Processing slice)
-- =============================================================================
-- Additive only. V001–V053 are never edited and no existing receipt row is
-- rewritten: financial snapshots, receipt ids and numbers stay untouched.
--
-- Why a migration is required (spec §3/§13): the Receipt lifecycle needs an
-- explicit PROCESSING state plus attempt/diagnostic metadata so retries are
-- bounded and observable. None of that exists on payment.receipts (V007), and
-- the status CHECK rejects PROCESSING. Object metadata columns are added so the
-- issued document's mime type/size/checksum can be reported without exposing
-- the internal storage key.
--
-- 1. Lifecycle/diagnostic and worker-claim columns (mirrors V047's claim model).
-- 2. Status CHECK extended with PROCESSING.
-- 3. Bounded recovery indexes.
-- =============================================================================

ALTER TABLE payment.receipts
    ADD COLUMN IF NOT EXISTS attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_attempt_at timestamptz,
    ADD COLUMN IF NOT EXISTS failure_reason varchar(500),
    ADD COLUMN IF NOT EXISTS processing_owner varchar(120),
    ADD COLUMN IF NOT EXISTS processing_token varchar(120),
    ADD COLUMN IF NOT EXISTS processing_started_at timestamptz,
    ADD COLUMN IF NOT EXISTS mime_type varchar(120),
    ADD COLUMN IF NOT EXISTS size_bytes bigint,
    ADD COLUMN IF NOT EXISTS checksum varchar(128);

ALTER TABLE payment.receipts
    DROP CONSTRAINT IF EXISTS ck_receipt_status;
ALTER TABLE payment.receipts
    ADD CONSTRAINT ck_receipt_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'ISSUED', 'FAILED')
    );

ALTER TABLE payment.receipts
    DROP CONSTRAINT IF EXISTS ck_receipt_attempts;
ALTER TABLE payment.receipts
    ADD CONSTRAINT ck_receipt_attempts CHECK (attempts >= 0);

-- PENDING rows older than the safe threshold are stranded requests.
CREATE INDEX IF NOT EXISTS ix_receipt_issuance_pending
    ON payment.receipts (created_at)
    WHERE status = 'PENDING';

-- FAILED retries are selected by their last attempt (backoff window).
CREATE INDEX IF NOT EXISTS ix_receipt_issuance_retry
    ON payment.receipts (last_attempt_at)
    WHERE status = 'FAILED';

-- Stale-claim sweep for PROCESSING rows whose owner died mid-attempt.
CREATE INDEX IF NOT EXISTS ix_receipt_processing_claim
    ON payment.receipts (processing_started_at)
    WHERE processing_started_at IS NOT NULL;
