-- =============================================================================
-- V046: Cart -> Order reservation conversion and exactly-once consumption (2D)
-- =============================================================================
-- Slice 2C left checkout with a release gap: the cart holds were given back and
-- the order re-reserved whatever the product-only RecipeDemandService computed,
-- so a configured variant or a milk/sugar/extra-shot effect could be lost.
-- Slice 2D converts the persisted cart holds into the order reservation instead:
-- a metadata-only ownership transition with no balance movement and therefore
-- no window in which the stock is unheld.
--
-- Extensions (all additive / idempotent, nothing is backfilled or rewritten):
--   inventory_reservations.status vocabulary gains CONVERTED (cart hold whose
--       demand now belongs to an order, kept for audit)
--   inventory_reservations.converted_at / converted_order_id /
--       converted_order_line_id / converted_to_reservation_id : the conversion
--       lineage from one cart line to the single order reservation
--   inventory_reservations.consumed_by : the actor credited with consuming
--   ordering.order_items.cart_item_id / variant_id : the order-line side of the
--       same lineage (product id and customizations were already snapshotted)
--   uk_stock_consumption_reservation : at most ONE SALE_CONSUMPTION row may name
--       a given reservation, so a double consumption cannot be committed even if
--       a service guard were bypassed
--
-- Deliberate non-goals:
--   * existing cart and order reservations are untouched (no automatic
--     conversion: only a checkout may convert, and it decides per cart line)
--   * balances and ledger history are untouched
--   * no new reservation table, no parallel checkout path
--
-- cart_item_id on order_items intentionally has NO foreign key: cart items are
-- hard-deleted with their cart line (V045 note) and the order snapshot must
-- survive that. variant_id does get a foreign key because catalog variants are
-- soft-deleted, never removed.
-- =============================================================================

ALTER TABLE inventory.inventory_reservations
    ADD COLUMN IF NOT EXISTS converted_at timestamptz,
    ADD COLUMN IF NOT EXISTS converted_order_id uuid REFERENCES ordering.orders (order_id),
    ADD COLUMN IF NOT EXISTS converted_order_line_id uuid REFERENCES ordering.order_items (order_item_id),
    ADD COLUMN IF NOT EXISTS converted_to_reservation_id uuid
        REFERENCES inventory.inventory_reservations (reservation_id),
    ADD COLUMN IF NOT EXISTS consumed_by uuid;

ALTER TABLE inventory.inventory_reservations
    DROP CONSTRAINT IF EXISTS ck_reservation_status;

ALTER TABLE inventory.inventory_reservations
    ADD CONSTRAINT ck_reservation_status CHECK (
        status IN (
            'ACTIVE',
            'CONSUMED',
            'RELEASED',
            'EXPIRED',
            'CANCELLED',
            'CONVERTED'
        )
    );

-- One order line is fed by at most one converted cart hold: a second conversion
-- of the same cart line would double count the same demand.
CREATE UNIQUE INDEX IF NOT EXISTS uk_reservation_converted_line
    ON inventory.inventory_reservations (converted_order_line_id)
    WHERE converted_order_line_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_reservation_converted_order
    ON inventory.inventory_reservations (converted_order_id)
    WHERE converted_order_id IS NOT NULL;

-- ---------------------------------------------- order line provenance (2D §6)
ALTER TABLE ordering.order_items
    ADD COLUMN IF NOT EXISTS cart_item_id uuid,
    ADD COLUMN IF NOT EXISTS variant_id uuid REFERENCES catalog.product_variants (variant_id);

CREATE INDEX IF NOT EXISTS ix_order_items_cart_item
    ON ordering.order_items (cart_item_id)
    WHERE cart_item_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_order_items_variant
    ON ordering.order_items (variant_id)
    WHERE variant_id IS NOT NULL;

-- ------------------------------------------------- exactly-once consumption
-- The service transitions the reservation with a pessimistic write lock; this
-- index is the hard backstop that makes "SALE_CONSUMPTION happens once" true
-- even for a code path that forgot to check the reservation status.
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_consumption_reservation
    ON inventory.stock_transactions (reservation_id)
    WHERE transaction_type = 'SALE_CONSUMPTION' AND reservation_id IS NOT NULL;
