-- =============================================================================
-- V035: Inventory inter-branch stock transfers (M2)
-- =============================================================================
-- Adds the transfer aggregate that moves physical stock between two branches
-- the actor is entitled to. Dispatch decrements the SOURCE balance and receive
-- increments the DEST balance — both through the same guarded native mutations
-- the manual-adjustment path already uses, so no silent/negative stock is ever
-- possible and each move writes one ledger transaction.
--
--   inventory.stock_transfers        transfer header, source/dest, status machine
--   inventory.stock_transfer_lines   moved ingredients (unique transfer x ingredient)
--
-- Additive + idempotent. Quantities numeric(18,4), costs numeric(14,4) to match
-- the balance ledger. The ledger CHECK ck_stock_type is re-created to admit
-- TRANSFER_IN and TRANSFER_OUT alongside the existing types.
-- =============================================================================

CREATE TABLE IF NOT EXISTS inventory.stock_transfers (
    transfer_id uuid PRIMARY KEY,
    transfer_number varchar(80) NOT NULL UNIQUE,
    source_store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    dest_store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    status varchar(20) NOT NULL,
    notes varchar(1000),
    requested_by_user_id uuid REFERENCES identity.users (user_id),
    dispatched_at timestamptz,
    received_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_transfer_status CHECK (
        status IN ('REQUESTED', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED')
    ),
    CONSTRAINT ck_transfer_distinct_stores CHECK (source_store_id <> dest_store_id)
);

CREATE INDEX IF NOT EXISTS ix_transfer_source ON inventory.stock_transfers (source_store_id, status);
CREATE INDEX IF NOT EXISTS ix_transfer_dest ON inventory.stock_transfers (dest_store_id, status);

CREATE TABLE IF NOT EXISTS inventory.stock_transfer_lines (
    stock_transfer_line_id uuid PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES inventory.stock_transfers (transfer_id),
    ingredient_id uuid NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    quantity numeric(18, 4) NOT NULL,
    unit_cost numeric(14, 4) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (transfer_id, ingredient_id),
    CONSTRAINT ck_transfer_line_qty CHECK (quantity > 0),
    CONSTRAINT ck_transfer_line_cost CHECK (unit_cost >= 0)
);

CREATE INDEX IF NOT EXISTS ix_transfer_line_transfer ON inventory.stock_transfer_lines (transfer_id);

-- ---------------------------------------------- expand ledger type for transfers
-- Dispatch/receive record TRANSFER_OUT (source) and TRANSFER_IN (dest) moves on
-- the existing ledger. Re-create ck_stock_type to admit both, keeping every
-- prior type valid (drop-if-exists then add keeps the statement idempotent).
ALTER TABLE inventory.stock_transactions
    DROP CONSTRAINT IF EXISTS ck_stock_type;

ALTER TABLE inventory.stock_transactions
    ADD CONSTRAINT ck_stock_type CHECK (
        transaction_type IN (
            'STOCK_IN',
            'ADJUSTMENT',
            'SALE_CONSUMPTION',
            'RETURN',
            'DAMAGED',
            'PURCHASE_RECEIPT',
            'TRANSFER_IN',
            'TRANSFER_OUT'
        )
    );
