-- =============================================================================
-- V033: Inventory purchasing — suppliers, purchase orders, goods receipts
-- =============================================================================
-- Opens the procurement side of the inventory module on top of the existing
-- ingredient-centric ledger (V005/V030). Receiving a purchase order is the
-- valued stock-in that the aggregate balance ledger already models via guarded
-- native mutations; this migration adds the master/transactional tables only.
--
--   inventory.suppliers              supplier master (soft-deactivate, no delete)
--   inventory.purchase_orders        PO header, per store, status machine
--   inventory.purchase_order_lines   ordered ingredients (unique PO x ingredient)
--   inventory.goods_receipts         receipt header against a PO
--   inventory.goods_receipt_lines    per-line received qty/cost/lot/expiry
--
-- Additive + idempotent (CREATE TABLE IF NOT EXISTS, CREATE INDEX IF NOT
-- EXISTS). Money numeric(14,4), quantities numeric(18,4) to match the balance
-- ledger. The ledger CHECK ck_stock_type is expanded to admit PURCHASE_RECEIPT
-- via a guarded DROP/ADD so receiving writes classify on the stock_transactions
-- type column. Nothing here touches Product/Category, reservations or POS.
-- =============================================================================

-- --------------------------------------------------------------- suppliers
CREATE TABLE IF NOT EXISTS inventory.suppliers (
    supplier_id uuid PRIMARY KEY,
    supplier_code varchar(50) NOT NULL UNIQUE,
    supplier_name varchar(150) NOT NULL,
    contact_name varchar(150),
    phone varchar(30),
    email varchar(150),
    address text,
    tax_id varchar(50),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    version bigint NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------- purchase orders
CREATE TABLE IF NOT EXISTS inventory.purchase_orders (
    purchase_order_id uuid PRIMARY KEY,
    po_number varchar(80) NOT NULL UNIQUE,
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    supplier_id uuid NOT NULL REFERENCES inventory.suppliers (supplier_id),
    status varchar(20) NOT NULL,
    ordered_at timestamptz NOT NULL DEFAULT current_timestamp,
    expected_at timestamptz,
    notes varchar(1000),
    created_by_user_id uuid REFERENCES identity.users (user_id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_po_status CHECK (
        status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED', 'CLOSED')
    )
);

CREATE INDEX IF NOT EXISTS ix_po_store_status
    ON inventory.purchase_orders (store_id, status);

CREATE TABLE IF NOT EXISTS inventory.purchase_order_lines (
    purchase_order_line_id uuid PRIMARY KEY,
    purchase_order_id uuid NOT NULL REFERENCES inventory.purchase_orders (purchase_order_id),
    ingredient_id uuid NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    quantity_ordered numeric(18, 4) NOT NULL,
    quantity_received numeric(18, 4) NOT NULL DEFAULT 0,
    unit_cost numeric(14, 4) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (purchase_order_id, ingredient_id),
    CONSTRAINT ck_po_line_qty CHECK (quantity_ordered > 0),
    CONSTRAINT ck_po_line_received CHECK (
        quantity_received >= 0
        AND quantity_received <= quantity_ordered
    ),
    CONSTRAINT ck_po_line_cost CHECK (unit_cost >= 0)
);

CREATE INDEX IF NOT EXISTS ix_po_line_po
    ON inventory.purchase_order_lines (purchase_order_id);

-- ---------------------------------------------------------- goods receipts
CREATE TABLE IF NOT EXISTS inventory.goods_receipts (
    goods_receipt_id uuid PRIMARY KEY,
    receipt_number varchar(80) NOT NULL UNIQUE,
    purchase_order_id uuid NOT NULL REFERENCES inventory.purchase_orders (purchase_order_id),
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    received_by_user_id uuid REFERENCES identity.users (user_id),
    received_at timestamptz NOT NULL DEFAULT current_timestamp,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS ix_receipt_po
    ON inventory.goods_receipts (purchase_order_id);

CREATE TABLE IF NOT EXISTS inventory.goods_receipt_lines (
    goods_receipt_line_id uuid PRIMARY KEY,
    goods_receipt_id uuid NOT NULL REFERENCES inventory.goods_receipts (goods_receipt_id),
    ingredient_id uuid NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    quantity numeric(18, 4) NOT NULL,
    unit_cost numeric(14, 4) NOT NULL DEFAULT 0,
    lot_number varchar(60),
    expiry_date date,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    CONSTRAINT ck_receipt_line_qty CHECK (quantity > 0),
    CONSTRAINT ck_receipt_line_cost CHECK (unit_cost >= 0)
);

CREATE INDEX IF NOT EXISTS ix_receipt_line_receipt
    ON inventory.goods_receipt_lines (goods_receipt_id);

-- ---------------------------------------------- expand ledger type for receiving
-- Receiving a PO is a valued stock-in recorded on the existing ledger. The
-- V005 ck_stock_type CHECK lists the original five types; re-create it to also
-- admit PURCHASE_RECEIPT. Guarded (drop-if-exists then add) so the statement is
-- idempotent and never strands the table without a constraint.
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
            'PURCHASE_RECEIPT'
        )
    );
