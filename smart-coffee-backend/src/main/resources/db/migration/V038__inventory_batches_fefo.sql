-- =============================================================================
-- V038: Batch / lot tracking + expiry + FEFO (M3)
-- =============================================================================
-- Adds an opt-in batch ledger that runs *beside* the aggregate balance ledger.
-- Batch tracking is enabled per ingredient (ingredients.track_batches, V036);
-- untracked ingredients and every existing path behave exactly as before. The
-- aggregate inventory_balances.current_stock stays authoritative for
-- availability; for tracked items the sum of ACTIVE batches is kept
-- synchronized with that aggregate (receiving opens/increments a batch,
-- FEFO consumption and write-offs draw it down through guarded statements).
--
--   inventory.inventory_batches  one lot at one store (unique store x ingredient x lot)
--
-- Each stock_transaction_line optionally records the batch it drew from so a
-- FEFO consumption is auditable at the batch level. Batch write-off / expiry is
-- a valued stock-out classified as DAMAGED on the existing ledger (the plan
-- reuses DAMAGED rather than minting a WASTE type), so ck_stock_type is
-- unchanged by this migration.
--
-- Additive + idempotent (CREATE TABLE/INDEX IF NOT EXISTS, ADD COLUMN IF NOT
-- EXISTS, guarded CHECK re-create). Quantities numeric(18,4), costs
-- numeric(14,4) to match the balance ledger. Nothing here touches
-- Product/Category, reservations, POS or the aggregate guards.
-- =============================================================================

CREATE TABLE IF NOT EXISTS inventory.inventory_batches (
    batch_id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    ingredient_id uuid NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    lot_number varchar(60) NOT NULL,
    expiry_date date,
    quantity_base numeric(18, 4) NOT NULL DEFAULT 0,
    unit_cost numeric(14, 4) NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    received_at timestamptz NOT NULL DEFAULT current_timestamp,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (store_id, ingredient_id, lot_number),
    CONSTRAINT ck_batch_status CHECK (
        status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED', 'ADJUSTED_OUT')
    ),
    -- A batch is only ACTIVE while it still holds stock; EXHAUSTED/EXPIRED/
    -- ADJUSTED_OUT retire it at (or toward) zero. quantity_base is allowed to
    -- sit at exactly zero so FEFO can fully draw a lot without violating the
    -- CHECK (the >0 the plan sketches is relaxed here for the EXHAUSTED state).
    CONSTRAINT ck_batch_qty CHECK (quantity_base >= 0),
    CONSTRAINT ck_batch_cost CHECK (unit_cost >= 0)
);

-- Accelerate the FEFO draw (earliest unexpired ACTIVE lot first) and the
-- per-store/per-ingredient batch listing.
CREATE INDEX IF NOT EXISTS ix_batch_fefo
    ON inventory.inventory_batches (store_id, ingredient_id, expiry_date)
    WHERE status = 'ACTIVE';

-- ---------------------------------------------- link ledger lines to a batch
-- A FEFO consumption or write-off stamps the batch it drew on the corresponding
-- transaction line. Nullable: untracked ingredients keep writing batch-less
-- lines, so no existing ledger row is affected.
ALTER TABLE inventory.stock_transaction_lines
    ADD COLUMN IF NOT EXISTS batch_id uuid REFERENCES inventory.inventory_batches (batch_id);

CREATE INDEX IF NOT EXISTS ix_stock_line_batch
    ON inventory.stock_transaction_lines (batch_id)
    WHERE batch_id IS NOT NULL;
