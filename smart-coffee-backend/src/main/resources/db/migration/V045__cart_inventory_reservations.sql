-- =============================================================================
-- V045: Cart-owned inventory reservations (Slice 2C)
-- =============================================================================
-- Slice 2A/2B reserved stock at CHECKOUT only: inventory.inventory_reservations
-- was strictly order-keyed (order_id NOT NULL UNIQUE), so nothing existed for a
-- cart that had not been checked out yet. Slice 2C reserves the authoritative
-- ConsumptionPlan demand at cart-add time, so the SAME table is extended with
-- cart ownership instead of introducing a second, parallel reservation store
-- ("one authoritative source for active reservations").
--
-- Extensions (all additive / idempotent):
--   inventory_reservations.cart_id / cart_item_id / demand_revision /
--       source_type / source_recipe_version_id / source_recipe_version_number /
--       resolution_source / idempotency_key / release_reason
--   inventory_reservations.order_id becomes nullable (cart rows have no order)
--   status vocabulary gains CANCELLED (cart cancellation path)
--   inventory_reservation_lines.base_unit_code (readable snapshot unit)
--   stock_transactions.cart_id / cart_item_id / reservation_id /
--       idempotency_key (SALE_RESERVATION / SALE_RELEASE traceability)
--   ck_stock_type admits SALE_RESERVATION and SALE_RELEASE
--
-- Deliberate non-goals (Slice 2D):
--   * no SALE_CONSUMPTION change, no quantityOnHand movement here
--   * no backfill of reservations for carts that existed before this migration;
--     pre-existing cart lines stay legacy-unreserved until their next mutation
--     (silently reserving uncertain historical demand would be unsafe)
--   * existing balances, ledger rows, orders and carts are untouched
--
-- cart_item_id intentionally has NO foreign key: the cart lifecycle hard-deletes
-- cart items (orphanRemoval on Cart.items), and an audit row must never be
-- deleted or nulled by that. The reference is a stable audit pointer, exactly
-- like stock_transactions.cart_item_id. cart_id does get a foreign key because
-- cart rows are never deleted.
-- =============================================================================

ALTER TABLE inventory.inventory_reservations
    ADD COLUMN IF NOT EXISTS cart_id uuid REFERENCES cart.carts (cart_id),
    ADD COLUMN IF NOT EXISTS cart_item_id uuid,
    ADD COLUMN IF NOT EXISTS demand_revision bigint NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS source_type varchar(30) NOT NULL DEFAULT 'ORDER',
    ADD COLUMN IF NOT EXISTS source_recipe_version_id uuid REFERENCES recipe.recipe_versions (recipe_version_id),
    ADD COLUMN IF NOT EXISTS source_recipe_version_number integer,
    ADD COLUMN IF NOT EXISTS resolution_source varchar(20),
    ADD COLUMN IF NOT EXISTS idempotency_key varchar(200),
    ADD COLUMN IF NOT EXISTS release_reason varchar(60);

-- Cart-owned rows carry no order; order-owned rows are unchanged.
ALTER TABLE inventory.inventory_reservations
    ALTER COLUMN order_id DROP NOT NULL;

ALTER TABLE inventory.inventory_reservations
    DROP CONSTRAINT IF EXISTS ck_reservation_status;

ALTER TABLE inventory.inventory_reservations
    ADD CONSTRAINT ck_reservation_status CHECK (
        status IN (
            'ACTIVE',
            'CONSUMED',
            'RELEASED',
            'EXPIRED',
            'CANCELLED'
        )
    );

-- A reservation is always attributable to an owner: an order (checkout) or a
-- cart line (pre-checkout).
ALTER TABLE inventory.inventory_reservations
    DROP CONSTRAINT IF EXISTS ck_reservation_owner;

ALTER TABLE inventory.inventory_reservations
    ADD CONSTRAINT ck_reservation_owner CHECK (
        order_id IS NOT NULL
        OR cart_id IS NOT NULL
    );

-- A cart line owns exactly ONE reservation row for its whole lifetime; every
-- configuration/quantity change mutates that row and bumps demand_revision
-- (the released/re-reserved history lives on the ledger, not in duplicate rows).
CREATE UNIQUE INDEX IF NOT EXISTS uk_reservation_cart_item
    ON inventory.inventory_reservations (cart_item_id)
    WHERE cart_item_id IS NOT NULL;

-- Active-reservation lookups by cart (remove / clear / cancel / expiry release).
CREATE INDEX IF NOT EXISTS ix_reservation_cart_active
    ON inventory.inventory_reservations (cart_id)
    WHERE status = 'ACTIVE';

ALTER TABLE inventory.inventory_reservation_lines
    ADD COLUMN IF NOT EXISTS base_unit_code varchar(20);

-- ------------------------------------------- cart line variant (Slice 2C)
-- A cart line may be configured against an explicit sellable variant so recipe
-- resolution can use the variant override. Nullable: every existing line and
-- every product-level add keeps resolving through the product fallback.
ALTER TABLE cart.cart_items
    ADD COLUMN IF NOT EXISTS variant_id uuid REFERENCES catalog.product_variants (variant_id);

CREATE INDEX IF NOT EXISTS ix_cart_items_variant
    ON cart.cart_items (variant_id)
    WHERE variant_id IS NOT NULL;

-- ------------------------------------------------ ledger traceability (2C)
ALTER TABLE inventory.stock_transactions
    ADD COLUMN IF NOT EXISTS cart_id uuid,
    ADD COLUMN IF NOT EXISTS cart_item_id uuid,
    ADD COLUMN IF NOT EXISTS reservation_id uuid REFERENCES inventory.inventory_reservations (reservation_id),
    ADD COLUMN IF NOT EXISTS idempotency_key varchar(200);

CREATE INDEX IF NOT EXISTS ix_stock_transactions_cart
    ON inventory.stock_transactions (cart_id, occurred_at DESC)
    WHERE cart_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_stock_transactions_reservation
    ON inventory.stock_transactions (reservation_id)
    WHERE reservation_id IS NOT NULL;

-- SALE_RESERVATION / SALE_RELEASE move the *reserved* balance only. Re-create
-- the V005/V035 vocabulary so reservation moves classify on the ledger; every
-- existing type stays valid.
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
            'TRANSFER_OUT',
            'SALE_RESERVATION',
            'SALE_RELEASE'
        )
    );
