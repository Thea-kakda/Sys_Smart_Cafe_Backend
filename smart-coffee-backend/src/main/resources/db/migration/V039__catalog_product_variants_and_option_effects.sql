-- =============================================================================
-- V039: Product variants + product type / stock mode + customization-option
-- inventory effects.
--
-- Additive + backward-compatible.
--   * Existing products keep working: new columns are NOT NULL with a DEFAULT,
--     so pre-existing rows are backfilled to STANDARD / MADE_TO_ORDER without
--     any data rewrite or new table entry.
--   * Existing options keep working: new inventory-effect columns are nullable
--     with NO_EFFECT default, meaning "does not touch stock". No option is
--     required to declare a target ingredient.
--   * No product is required to declare variants. Products with zero rows in
--     catalog.product_variants continue to be sold at their base price via
--     the existing StoreProduct / catalog flow. A later service layer will
--     only enforce "one default active variant when variants are required"
--     once the Dashboard is upgraded to create them.
--
-- Money / quantity precision mirrors existing project rules:
--   * numeric(14,4) for prices (matches catalog.products.base_price).
--   * numeric(14,4) for ingredient quantities (matches inventory tables).
--   * numeric(7,4) for option effect multiplier (sugar percentages 0.0000-1.5000
--     and similar fit comfortably).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Product master: type + stock mode.
--    Constrained via CHECK so downstream code can rely on enum parity; the
--    Java layer maps these to ProductType / StockMode enums.
-- ---------------------------------------------------------------------------
ALTER TABLE catalog.products
    ADD COLUMN IF NOT EXISTS product_type varchar(20) NOT NULL DEFAULT 'STANDARD';

ALTER TABLE catalog.products
    ADD COLUMN IF NOT EXISTS stock_mode varchar(20) NOT NULL DEFAULT 'MADE_TO_ORDER';

-- Backfill any pre-existing rows explicitly (DEFAULT already did this, but the
-- UPDATE is idempotent and makes the migration self-documenting).
UPDATE catalog.products SET product_type = 'STANDARD'   WHERE product_type IS NULL;
UPDATE catalog.products SET stock_mode   = 'MADE_TO_ORDER' WHERE stock_mode   IS NULL;

ALTER TABLE catalog.products
    DROP CONSTRAINT IF EXISTS ck_products_product_type;
ALTER TABLE catalog.products
    ADD CONSTRAINT ck_products_product_type CHECK (
        product_type IN ('STANDARD','BEVERAGE','BAKERY','CAKE','FOOD','COMBO','SERVICE')
    );

ALTER TABLE catalog.products
    DROP CONSTRAINT IF EXISTS ck_products_stock_mode;
ALTER TABLE catalog.products
    ADD CONSTRAINT ck_products_stock_mode CHECK (
        stock_mode IN ('MADE_TO_ORDER','RECIPE_BASED','FINISHED_GOOD','BATCH_PRODUCTION','NOT_TRACKED')
    );

CREATE INDEX IF NOT EXISTS ix_products_type_mode ON catalog.products (product_type, stock_mode);

-- ---------------------------------------------------------------------------
-- 2. Product variants (sellable form with its own SKU / price / recipe).
--    A product may have zero variants (base-price products like bottled water
--    with no size split) or many. When present, exactly one ACTIVE variant may
--    be flagged is_default per product; enforced by a partial unique index so
--    historical rows (all default=false) are unaffected.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS catalog.product_variants (
    variant_id       uuid PRIMARY KEY,
    product_id       uuid NOT NULL REFERENCES catalog.products(product_id) ON DELETE CASCADE,
    variant_code     varchar(80)  NOT NULL,
    variant_name     varchar(150) NOT NULL,
    description      text,
    barcode          varchar(64),
    price            numeric(14,4) NOT NULL,
    cost             numeric(14,4),
    currency_code    varchar(3)    NOT NULL,
    is_default       boolean       NOT NULL DEFAULT false,
    active           boolean       NOT NULL DEFAULT true,
    display_order    integer       NOT NULL DEFAULT 0,
    image_object_key varchar(255),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    deleted_at       timestamptz,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ck_variant_price_non_negative CHECK (price >= 0),
    CONSTRAINT ck_variant_cost_non_negative  CHECK (cost IS NULL OR cost >= 0),
    CONSTRAINT uk_variant_code_per_product   UNIQUE (product_id, variant_code)
);

-- One default ACTIVE variant per product (partial unique; historical rows all
-- have is_default=false so this constraint cannot collide with existing data).
CREATE UNIQUE INDEX IF NOT EXISTS uk_variant_one_default_per_product
    ON catalog.product_variants (product_id)
    WHERE is_default = true AND active = TRUE AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_variants_product_active
    ON catalog.product_variants (product_id, active)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_variants_barcode
    ON catalog.product_variants (barcode)
    WHERE barcode IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Customization-option inventory effects.
--    Nullable so no existing option is forced to declare a stock impact.
--    Semantics:
--      NONE / NULL   : the option changes price only, not stock.
--      ADD           : consume effect_quantity of target_ingredient_id.
--      REMOVE        : reduce the base recipe by effect_quantity of the target.
--      REPLACE       : swap replaces_option_id's consumption for this option's
--                      (target_ingredient_id / effect_quantity) consumption.
--      MULTIPLY      : scale the base recipe's sugar/ingredient row by
--                      effect_multiplier. Used for sugar percentage (0.00/0.50/
--                      1.00/1.50) and similar scaling rules.
--    Cross-column CHECK keeps each effect type consistent with the fields it
--    requires without forcing unrelated options to fill them.
-- ---------------------------------------------------------------------------
ALTER TABLE catalog.customization_options
    ADD COLUMN IF NOT EXISTS inventory_effect_type varchar(15) NOT NULL DEFAULT 'NONE';
ALTER TABLE catalog.customization_options
    ADD COLUMN IF NOT EXISTS target_ingredient_id uuid NULL;
ALTER TABLE catalog.customization_options
    ADD COLUMN IF NOT EXISTS effect_quantity numeric(14,4) NULL;
ALTER TABLE catalog.customization_options
    ADD COLUMN IF NOT EXISTS effect_multiplier numeric(7,4) NULL;
ALTER TABLE catalog.customization_options
    ADD COLUMN IF NOT EXISTS replaces_option_id uuid NULL;

ALTER TABLE catalog.customization_options
    DROP CONSTRAINT IF EXISTS ck_option_effect_type;
ALTER TABLE catalog.customization_options
    ADD CONSTRAINT ck_option_effect_type CHECK (
        inventory_effect_type IN ('NONE','ADD','REMOVE','REPLACE','MULTIPLY')
    );

-- Type-conditional shape guards (only the fields each effect actually needs).
ALTER TABLE catalog.customization_options
    DROP CONSTRAINT IF EXISTS ck_option_effect_shape;
ALTER TABLE catalog.customization_options
    ADD CONSTRAINT ck_option_effect_shape CHECK (
        (inventory_effect_type = 'NONE')
        OR (inventory_effect_type = 'MULTIPLY'
                AND effect_multiplier IS NOT NULL
                AND effect_multiplier >= 0
                AND (target_ingredient_id IS NULL OR target_ingredient_id IS NOT NULL))
        OR (inventory_effect_type IN ('ADD','REMOVE')
                AND target_ingredient_id IS NOT NULL
                AND effect_quantity IS NOT NULL
                AND effect_quantity >= 0)
        OR (inventory_effect_type = 'REPLACE'
                AND target_ingredient_id IS NOT NULL
                AND replaces_option_id IS NOT NULL
                AND (effect_quantity IS NULL OR effect_quantity >= 0))
    );

-- Optional FK (nullable column; only enforced when populated).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_option_target_ingredient'
    ) THEN
        ALTER TABLE catalog.customization_options
            ADD CONSTRAINT fk_option_target_ingredient
            FOREIGN KEY (target_ingredient_id)
            REFERENCES inventory.ingredients(ingredient_id)
            ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_option_replaces_option'
    ) THEN
        ALTER TABLE catalog.customization_options
            ADD CONSTRAINT fk_option_replaces_option
            FOREIGN KEY (replaces_option_id)
            REFERENCES catalog.customization_options(option_id)
            ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_options_effect_type
    ON catalog.customization_options (inventory_effect_type)
    WHERE inventory_effect_type <> 'NONE';
