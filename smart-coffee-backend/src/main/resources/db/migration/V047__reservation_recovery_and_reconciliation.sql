-- =============================================================================
-- V047: Reservation recovery, reconciliation and operational hardening (Slice 2E)
-- =============================================================================
-- This slice strengthens the EXISTING reservation/balance/ledger architecture;
-- it introduces no parallel source of truth. Everything added here is either
--   (a) a worker claim column on the reservation row (multi-node-safe cleanup),
--   (b) a report/audit table (reconciliation runs + findings), or
--   (c) a permission seed.
-- None of them is authoritative for stock: balances and reservations stay the
-- single owners of reserved/available quantity (Core Invariants, spec §3).
--
-- Additive / idempotent. V001–V046 are never edited. No existing balance or
-- reservation row is altered by this migration (no data DML, only DDL + seeds).
--
-- 1. inventory_reservations worker-claim columns (spec §5, §19)
--    Claim metadata mirrors integration.outbox_events (V012) so the ORDER-
--    reservation expiry worker is multi-node safe with FOR UPDATE SKIP LOCKED
--    plus an reclaimable stale-claim window; it does NOT hold one transaction
--    across the whole batch.
-- 2. inventory_reconciliation_runs / _findings (spec §7, §19, §20)
--    Dry-run reports and audit records ONLY. A finding never mutates stock; the
--    repair endpoints reuse the existing finalization service, and the run/
--    finding rows simply describe what was seen and what (if anything) was done.
-- 3. reservation:release + reconciliation:view/run/repair permissions (§14)
--    inventory:read / inventory:adjust already exist (V013/V030) and continue to
--    guard balance reads and manual adjustments; these new codes cover the
--    actions no existing permission represents (release a hold, view/run/repair
--    a reconciliation) so we do not overload an unrelated code.
-- =============================================================================

-- -------------------------------------------------------- 1. worker-claim columns
ALTER TABLE inventory.inventory_reservations
    ADD COLUMN IF NOT EXISTS processing_started_at timestamptz,
    ADD COLUMN IF NOT EXISTS processing_owner varchar(120),
    ADD COLUMN IF NOT EXISTS processing_token varchar(120),
    ADD COLUMN IF NOT EXISTS last_processing_error varchar(500),
    ADD COLUMN IF NOT EXISTS processing_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_reconciled_at timestamptz,
    ADD COLUMN IF NOT EXISTS reconciliation_status varchar(20);

-- reconciliation_status is a bounded cache of the last dry-run verdict for this
-- reservation, NOT an authority. Vocabulary mirrors the finding severity model.
ALTER TABLE inventory.inventory_reservations
    DROP CONSTRAINT IF EXISTS ck_reservation_reconciliation_status;
ALTER TABLE inventory.inventory_reservations
    ADD CONSTRAINT ck_reservation_reconciliation_status CHECK (
        reconciliation_status IS NULL
        OR reconciliation_status IN ('CLEAN', 'ANOMALY', 'REPAIRABLE', 'MANUAL_REVIEW', 'REPAIRED')
    );

-- One processing attempt counter can never go negative.
ALTER TABLE inventory.inventory_reservations
    DROP CONSTRAINT IF EXISTS ck_reservation_processing_attempts;
ALTER TABLE inventory.inventory_reservations
    ADD CONSTRAINT ck_reservation_processing_attempts CHECK (processing_attempts >= 0);

-- Claim candidate index: mirror of the outbox claim index (V012). Order-owned
-- ACTIVE holds ordered by expiry, so the SKIP LOCKED batch stays an index scan.
CREATE INDEX IF NOT EXISTS ix_reservation_expiry_claim
    ON inventory.inventory_reservations (expires_at)
    WHERE status = 'ACTIVE' AND source_type = 'ORDER';

-- Stale-claim recovery sweep: find rows still PROCESSING past their lease.
CREATE INDEX IF NOT EXISTS ix_reservation_processing_claim
    ON inventory.inventory_reservations (processing_started_at)
    WHERE processing_started_at IS NOT NULL;

-- -------------------------------------------------------- 2. reconciliation runs
CREATE TABLE IF NOT EXISTS inventory.inventory_reconciliation_runs (
    run_id uuid PRIMARY KEY,
    -- Nullable: a global run is authorized for administrators only (spec §13).
    store_id uuid REFERENCES organization.stores (store_id),
    -- varchar(30): must fit COMPLETED_WITH_FINDINGS (23 chars) — the terminal
    -- status a scan records when it finds anomalies.
    status varchar(30) NOT NULL,
    dry_run boolean NOT NULL DEFAULT true,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    scanned_count integer NOT NULL DEFAULT 0,
    anomaly_count integer NOT NULL DEFAULT 0,
    auto_repairable_count integer NOT NULL DEFAULT 0,
    manual_review_count integer NOT NULL DEFAULT 0,
    repaired_count integer NOT NULL DEFAULT 0,
    failed_repair_count integer NOT NULL DEFAULT 0,
    initiated_by uuid REFERENCES identity.users (user_id),
    -- Serialized request parameters (storeId, anomalyType filter, maxItems …) as
    -- a diagnostic snapshot only; never read back as authoritative state.
    parameters jsonb,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_recon_run_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'COMPLETED_WITH_FINDINGS', 'FAILED')
    ),
    CONSTRAINT ck_recon_run_counters CHECK (
        scanned_count >= 0
        AND anomaly_count >= 0
        AND auto_repairable_count >= 0
        AND manual_review_count >= 0
        AND repaired_count >= 0
        AND failed_repair_count >= 0
    )
);

CREATE INDEX IF NOT EXISTS ix_recon_run_store
    ON inventory.inventory_reconciliation_runs (store_id, started_at DESC);

-- ----------------------------------------------------- 2b. reconciliation findings
CREATE TABLE IF NOT EXISTS inventory.inventory_reconciliation_findings (
    finding_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES inventory.inventory_reconciliation_runs (run_id),
    -- Nullable: an orphan reservation line (parent gone) cannot be attributed to
    -- a store; every other detector fills it and the store-scoped index/queries
    -- simply exclude null-store rows.
    store_id uuid REFERENCES organization.stores (store_id),
    -- Both nullable: a balance-vs-derived mismatch may name no single
    -- reservation, and an orphan reservation may name no inventory item.
    inventory_item_id uuid REFERENCES inventory.ingredients (ingredient_id),
    reservation_id uuid REFERENCES inventory.inventory_reservations (reservation_id),
    anomaly_type varchar(60) NOT NULL,
    severity varchar(20) NOT NULL,
    -- Expected vs actual quantities (derived reserved vs recorded reserved, or a
    -- single reservation's held vs ledgered amount). Diagnostic; never authoritative.
    expected_quantity numeric(18, 4),
    actual_quantity numeric(18, 4),
    repairable boolean NOT NULL DEFAULT false,
    status varchar(20) NOT NULL,
    opened_order_id uuid,
    opened_cart_id uuid,
    repaired_at timestamptz,
    repaired_by uuid,
    -- Non-empty reason is enforced for a manual repair at the service layer; the
    -- CHECK only guarantees a recorded repair is never reasonless.
    repair_reason varchar(500),
    -- Idempotency identity of the repair action, so a repeated repair call cannot
    -- apply the same correction twice.
    repair_idempotency_key varchar(200),
    -- Diagnostic metadata (affected transaction ids, snapshot detail). JSON only.
    detail jsonb,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_recon_finding_anomaly CHECK (
        anomaly_type IN (
            'RESERVED_BALANCE_MISMATCH',
            'ACTIVE_RESERVATION_WITHOUT_BALANCE',
            'BALANCE_WITHOUT_ACTIVE_RESERVATION',
            'ACTIVE_CART_RESERVATION_ON_CHECKED_OUT_CART',
            'CONVERTED_RESERVATION_WITHOUT_TARGET',
            'CONVERTED_QUANTITY_MISMATCH',
            'ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER',
            'ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER',
            'CONSUMED_RESERVATION_WITHOUT_CONSUMPTION_LEDGER',
            'DUPLICATE_CONSUMPTION_LEDGER',
            'RELEASED_RESERVATION_WITHOUT_RELEASE_LEDGER',
            'RELEASE_AND_CONSUMPTION_CONFLICT',
            'ORPHAN_RESERVATION',
            'ORPHAN_RESERVATION_LINE',
            'ORPHAN_LEDGER_REFERENCE',
            'EXPIRED_ACTIVE_RESERVATION',
            'NEGATIVE_BALANCE_STATE',
            'RESERVATION_STORE_MISMATCH'
        )
    ),
    CONSTRAINT ck_recon_finding_severity CHECK (
        severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')
    ),
    CONSTRAINT ck_recon_finding_status CHECK (
        status IN ('OPEN', 'REPAIRABLE', 'REPAIRED', 'MANUAL_REVIEW', 'SUPERSEDED', 'DISMISSED')
    ),
    -- A recorded repair must carry a reason. repaired_by is nullable on purpose:
    -- an automatic (system-initiated) batch repair records no user actor, whereas
    -- an operator repair additionally requires a non-null actor at the service layer.
    CONSTRAINT ck_recon_finding_repair_reason CHECK (
        status <> 'REPAIRED'
        OR length(btrim(repair_reason)) > 0
    )
);

-- At most one repair per (finding, idempotency key): repeated repair calls are
-- absorbed instead of double-applying. Partial over the nullable key so OPEN
-- findings (no key yet) are unaffected.
CREATE UNIQUE INDEX IF NOT EXISTS uk_recon_finding_repair_key
    ON inventory.inventory_reconciliation_findings (finding_id, repair_idempotency_key)
    WHERE repair_idempotency_key IS NOT NULL;

-- Pending-anomaly lookup (health indicator + repair queue) and store-scoped reads.
CREATE INDEX IF NOT EXISTS ix_recon_finding_open
    ON inventory.inventory_reconciliation_findings (store_id, severity, created_at DESC)
    WHERE status IN ('OPEN', 'REPAIRABLE', 'MANUAL_REVIEW');

CREATE INDEX IF NOT EXISTS ix_recon_finding_run
    ON inventory.inventory_reconciliation_findings (run_id);

CREATE INDEX IF NOT EXISTS ix_recon_finding_reservation
    ON inventory.inventory_reconciliation_findings (reservation_id)
    WHERE reservation_id IS NOT NULL;

-- ------------------------------------------------------------- 3. permissions
-- New capability codes (e500… namespace, following the e400… inventory family).
INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('e5000000-0000-0000-0000-000000000001', 'reservation:release'),
    ('e5000000-0000-0000-0000-000000000002', 'reconciliation:view'),
    ('e5000000-0000-0000-0000-000000000003', 'reconciliation:run'),
    ('e5000000-0000-0000-0000-000000000004', 'reconciliation:repair')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join covers the four new codes).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER may release a hold and view/run/repair reconciliation for its
-- OWN store (store scoping is still enforced server-side). The global (cross-
-- store) scan is additionally gated to SUPER_ADMIN/ADMIN in the service layer.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code IN
    ('reservation:release', 'reconciliation:view', 'reconciliation:run', 'reconciliation:repair')
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
