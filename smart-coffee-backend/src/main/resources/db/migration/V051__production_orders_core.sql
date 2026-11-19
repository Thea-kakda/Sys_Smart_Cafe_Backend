-- =============================================================================
-- V051: Production Orders Core Backend — Phase 5A.1 (foundation, planning, start).
--
-- Slice 5A introduces a NEW production aggregate alongside the existing sale-
-- order ticket workflow (production.production_tickets). This phase creates the
-- tables and constraints, wires Product/Variant output inventory mapping, and
-- seeds permission codes. No inventory balance movement happens until Phase
-- 5A.2 (completion posting):
--
--   DRAFT       — editable header; no inventory movement
--   PLANNED     — RecipeVersion + material snapshot frozen; no inventory movement
--   IN_PROGRESS — lifecycle only in Phase 5A.1; no inventory movement
--   COMPLETED   — reserved for Phase 5A.2 (posts consumption + output + waste)
--   CANCELLED   — reserved for Phase 5A.2
--   CLOSED      — reserved for Phase 5A.2 (terminal, immutable)
--
-- Migration rules honoured:
--   * V001 through V050 are NEVER edited. This step is purely additive.
--   * ck_stock_type is DROP/ADD'd with the full prior vocabulary + the two new
--     PRODUCTION_* codes so existing ledger rows remain valid (same pattern
--     used by V033, V035, V045).
--   * All new columns on catalog tables are nullable; no backfill (spec
--     Decision 1 rule 7: "Do not automatically backfill mappings for legacy
--     Products"). Products without an explicit output mapping remain readable
--     and sellable via existing StockMode paths; production planning refuses
--     them with PRODUCTION_OUTPUT_ITEM_REQUIRED.
--   * Idempotent: CREATE TABLE IF NOT EXISTS, ADD COLUMN IF NOT EXISTS,
--     ON CONFLICT DO NOTHING, guarded DROP/ADD constraints.
--   * Existing production.production_tickets / _ticket_items / _stations are
--     untouched; sale-order workflow (§28) is fully preserved.
--
-- Naming:
--   * Permission namespace uses 92xx to sit adjacent to the existing 91xx
--     production-ticket codes (V008).
--   * Order number format is PO-yyyyMMdd-<seq padded to 6 digits>; date is
--     informational, uniqueness is guaranteed by a Postgres sequence so no
--     in-memory counter exists (spec §13, "Do not derive only from an in-
--     memory counter").
-- =============================================================================

-- --------------------------------------------------------- A. PO number seq
CREATE SEQUENCE IF NOT EXISTS production.production_order_number_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    NO CYCLE;

-- ------------------------------------------------------- B. orders aggregate
CREATE TABLE IF NOT EXISTS production.production_orders (
    production_order_id       uuid PRIMARY KEY,
    production_order_number   varchar(80)  NOT NULL UNIQUE,
    store_id                  uuid         NOT NULL REFERENCES organization.stores (store_id),
    product_id                uuid         NOT NULL REFERENCES catalog.products (product_id),
    variant_id                uuid         NULL REFERENCES catalog.product_variants (variant_id),
    -- Planning snapshot fields — set once when the order leaves DRAFT and
    -- frozen thereafter. Nullable while DRAFT so editing target/recipe is
    -- allowed before PLANNED.
    recipe_version_id         uuid         NULL REFERENCES recipe.recipe_versions (recipe_version_id),
    recipe_version_number     integer      NULL,
    -- Lifecycle
    status                    varchar(20)  NOT NULL DEFAULT 'DRAFT',
    -- Quantities: planned is required for planning; actual columns are populated
    -- in Phase 5A.2 completion posting. Base units are the ingredient's
    -- canonical unit (see inventory.units_of_measure.base_flag).
    planned_output_quantity   numeric(18,4) NOT NULL,
    planned_output_unit_id    uuid         NULL REFERENCES inventory.units_of_measure (unit_id),
    actual_output_quantity    numeric(18,4) NULL,
    -- Actor + timestamp columns for each lifecycle edge (spec §3).
    scheduled_for             timestamptz  NULL,
    started_at                timestamptz  NULL,
    started_by                uuid         NULL,
    completed_at              timestamptz  NULL,
    completed_by              uuid         NULL,
    cancelled_at              timestamptz  NULL,
    cancelled_by              uuid         NULL,
    cancellation_reason       varchar(500) NULL,
    closed_at                 timestamptz  NULL,
    closed_by                 uuid         NULL,
    close_note                varchar(500) NULL,
    -- Free-form
    notes                     text         NULL,
    -- Audit + optimistic locking
    created_at                timestamptz  NOT NULL DEFAULT now(),
    created_by                uuid         NULL,
    updated_at                timestamptz  NOT NULL DEFAULT now(),
    updated_by                uuid         NULL,
    version                   bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_production_order_status CHECK (
        status IN ('DRAFT','PLANNED','IN_PROGRESS','COMPLETED','CANCELLED','CLOSED')
    ),
    CONSTRAINT ck_production_order_planned_qty_positive CHECK (
        planned_output_quantity > 0
    ),
    CONSTRAINT ck_production_order_actual_qty_non_negative CHECK (
        actual_output_quantity IS NULL OR actual_output_quantity >= 0
    )
);
-- NOTE: The "variant belongs to product" invariant is enforced in the service
-- layer (validate on createDraft/plan) rather than as a composite foreign key,
-- because catalog.product_variants does not expose a (product_id, variant_id)
-- UNIQUE constraint that a composite FK would require. Adding such a UNIQUE
-- would be a broader schema change than Phase 5A.1 warrants; the DB CHECK
-- vocabulary + optimistic-lock version column already protect against the
-- realistic corruption vectors, and the service rejects a mismatch with a
-- stable PRODUCTION_ORDER_TARGET_MISMATCH validation error.

COMMENT ON TABLE production.production_orders IS
    'Planned manufacturing order (Slice 5A). Distinct from production.production_tickets, which is the sale-order-driven kitchen ticket workflow.';
COMMENT ON COLUMN production.production_orders.recipe_version_id IS
    'Authoritative RecipeVersion pinned at PLANNED transition. Never re-resolved after planning (spec §5).';
COMMENT ON COLUMN production.production_orders.recipe_version_number IS
    'Copy of recipe_versions.version_number at planning time so the snapshot is human-readable even if the version row is later archived.';

CREATE INDEX IF NOT EXISTS ix_production_orders_store_status
    ON production.production_orders (store_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_production_orders_product
    ON production.production_orders (product_id);
CREATE INDEX IF NOT EXISTS ix_production_orders_scheduled
    ON production.production_orders (scheduled_for)
    WHERE scheduled_for IS NOT NULL;

-- ------------------------------------------------- C. material snapshot rows
-- Immutable after PLANNED; quantity_consumed_base is written in Phase 5A.2
-- completion posting, never modified after that (spec §3: "Do not store
-- material snapshots only as unvalidated JSON").
CREATE TABLE IF NOT EXISTS production.production_order_materials (
    production_order_material_id uuid PRIMARY KEY,
    production_order_id          uuid         NOT NULL REFERENCES production.production_orders (production_order_id) ON DELETE CASCADE,
    inventory_item_id            uuid         NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    quantity_planned_base        numeric(18,4) NOT NULL,
    quantity_consumed_base       numeric(18,4) NULL,
    base_unit_code               varchar(20)  NOT NULL,
    source_recipe_line_id        uuid         NULL REFERENCES recipe.recipe_version_lines (recipe_version_line_id),
    wastage_allowance_pct        numeric(9,6) NOT NULL DEFAULT 0,
    display_order                integer      NOT NULL DEFAULT 0,
    created_at                   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_production_order_material_planned_positive CHECK (quantity_planned_base > 0),
    CONSTRAINT ck_production_order_material_consumed_non_negative CHECK (
        quantity_consumed_base IS NULL OR quantity_consumed_base >= 0
    )
);
COMMENT ON TABLE production.production_order_materials IS
    'Per-material planning snapshot for a Production Order. Rows are frozen once the order leaves DRAFT; consumed_base is filled exactly once at completion (Phase 5A.2).';

CREATE INDEX IF NOT EXISTS ix_po_materials_order ON production.production_order_materials (production_order_id, display_order);
CREATE INDEX IF NOT EXISTS ix_po_materials_item  ON production.production_order_materials (inventory_item_id);

-- ------------------------------------------------------- D. output snapshot
CREATE TABLE IF NOT EXISTS production.production_order_outputs (
    production_order_output_id  uuid PRIMARY KEY,
    production_order_id         uuid         NOT NULL REFERENCES production.production_orders (production_order_id) ON DELETE CASCADE,
    output_inventory_item_id    uuid         NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    planned_quantity            numeric(18,4) NOT NULL,
    actual_quantity             numeric(18,4) NULL,
    unit_code                   varchar(20)  NOT NULL,
    batch_id                    uuid         NULL REFERENCES inventory.inventory_batches (batch_id),
    expiry_date                 date         NULL,
    quality_status              varchar(30)  NULL,
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_production_order_output_planned_positive CHECK (planned_quantity > 0),
    CONSTRAINT ck_production_order_output_actual_non_negative CHECK (
        actual_quantity IS NULL OR actual_quantity >= 0
    )
);
COMMENT ON TABLE production.production_order_outputs IS
    'The intended finished-good output for a Production Order, resolved via Product/Variant.output_ingredient_id at planning (Decision 1). batch_id is populated in Phase 5A.2 when the output ingredient has track_batches=true.';

CREATE INDEX IF NOT EXISTS ix_po_outputs_order ON production.production_order_outputs (production_order_id);
CREATE INDEX IF NOT EXISTS ix_po_outputs_item  ON production.production_order_outputs (output_inventory_item_id);

-- ---------------------------------------------------------- E. waste rows
-- Domain shape exists in Phase 5A.1 so the aggregate can hold draft waste
-- entries; DAMAGED posting is deferred to Phase 5A.2.
CREATE TABLE IF NOT EXISTS production.production_order_waste (
    production_order_waste_id uuid PRIMARY KEY,
    production_order_id       uuid         NOT NULL REFERENCES production.production_orders (production_order_id) ON DELETE CASCADE,
    inventory_item_id         uuid         NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    quantity                  numeric(18,4) NOT NULL,
    unit_code                 varchar(20)  NOT NULL,
    reason_code               varchar(40)  NOT NULL,
    notes                     varchar(500) NULL,
    recorded_at               timestamptz  NULL,
    recorded_by               uuid         NULL,
    created_at                timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_production_order_waste_positive CHECK (quantity > 0),
    CONSTRAINT ck_production_order_waste_reason CHECK (
        reason_code IN ('SPOILED','BURNT','DROPPED','QUALITY_FAIL','OVER_PRODUCED','OTHER')
    )
);
COMMENT ON TABLE production.production_order_waste IS
    'Waste/damage rows for a Production Order. Recorded quantities are validated but no stock_transaction is written until Phase 5A.2 completion posting (uses DAMAGED with reference_type=PRODUCTION_ORDER_WASTE).';

CREATE INDEX IF NOT EXISTS ix_po_waste_order ON production.production_order_waste (production_order_id);

-- ------------------------------------ F. output mapping columns on catalog
-- Decision 1: nullable pointer per Product (fallback) and ProductVariant
-- (override). No backfill. Legacy products remain readable/sellable.
ALTER TABLE catalog.products
    ADD COLUMN IF NOT EXISTS output_ingredient_id uuid NULL;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_products_output_ingredient'
    ) THEN
        ALTER TABLE catalog.products
            ADD CONSTRAINT fk_products_output_ingredient
            FOREIGN KEY (output_ingredient_id)
            REFERENCES inventory.ingredients (ingredient_id);
    END IF;
END $$;
COMMENT ON COLUMN catalog.products.output_ingredient_id IS
    'Finished-good inventory item produced from this product (Slice 5A). Required when stock_mode IN (BATCH_PRODUCTION, FINISHED_GOOD) and a Production Order targets this product; otherwise NULL. Variant pointer takes precedence when set.';

ALTER TABLE catalog.product_variants
    ADD COLUMN IF NOT EXISTS output_ingredient_id uuid NULL;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_variants_output_ingredient'
    ) THEN
        ALTER TABLE catalog.product_variants
            ADD CONSTRAINT fk_product_variants_output_ingredient
            FOREIGN KEY (output_ingredient_id)
            REFERENCES inventory.ingredients (ingredient_id);
    END IF;
END $$;
COMMENT ON COLUMN catalog.product_variants.output_ingredient_id IS
    'Variant-level override of the product output mapping (Slice 5A). When set, production planning targets this ingredient instead of the product-level pointer. Variant pointer must belong to the same Product context (enforced in the service layer).';

-- ------------------------------------------------ G. ledger type check extend
-- Rebuild ck_stock_type with the full prior vocabulary + two new production
-- types. Every existing type is preserved (spec §28 no-regression: existing
-- SALE_*, PURCHASE_RECEIPT, TRANSFER_*, RETURN, DAMAGED, STOCK_IN, ADJUSTMENT
-- all remain valid). Phase 5A.1 declares the enum values but does NOT post
-- any rows using them; Phase 5A.2 wires the writers.
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
            'SALE_RELEASE',
            'PRODUCTION_CONSUMPTION',
            'PRODUCTION_OUTPUT'
        )
    );

-- -------------------------------------------------------- H. permission seeds
-- New capability codes (92xx namespace, adjacent to V008 production tickets).
INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('92000000-0000-0000-0000-000000000001', 'production-order:view'),
    ('92000000-0000-0000-0000-000000000002', 'production-order:create'),
    ('92000000-0000-0000-0000-000000000003', 'production-order:plan'),
    ('92000000-0000-0000-0000-000000000004', 'production-order:start'),
    ('92000000-0000-0000-0000-000000000005', 'production-order:complete'),
    ('92000000-0000-0000-0000-000000000006', 'production-order:cancel'),
    ('92000000-0000-0000-0000-000000000007', 'production-order:close')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
  AND p.permission_code IN (
      'production-order:view',
      'production-order:create',
      'production-order:plan',
      'production-order:start',
      'production-order:complete',
      'production-order:cancel',
      'production-order:close'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER holds all seven for its own store (server-side store scoping
-- still enforced by StoreAuthorizationService.requireStore on every command).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code IN (
    'production-order:view',
    'production-order:create',
    'production-order:plan',
    'production-order:start',
    'production-order:complete',
    'production-order:cancel',
    'production-order:close'
)
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Decision 3: No new PRODUCTION_OPERATOR role is created in this slice. No
-- existing production-capable role is inferred from display name (spec: "Do
-- not infer a role from a display name or create a role silently"). Cashier
-- and customer roles get no production-order permission.
-- =============================================================================
