-- ============================================================
-- V012: Fix transactional outbox claiming
-- ============================================================

ALTER TABLE integration.outbox_events
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(100);

ALTER TABLE integration.outbox_events
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

DROP INDEX IF EXISTS integration.ix_outbox_stale;

CREATE INDEX ix_outbox_stale
    ON integration.outbox_events
    (
        status,
        claimed_at
    )
    WHERE status = 'PROCESSING';