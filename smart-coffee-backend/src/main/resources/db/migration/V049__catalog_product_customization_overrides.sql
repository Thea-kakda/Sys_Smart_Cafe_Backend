-- =============================================================================
-- V049: Per-product customization overrides (Slice 4A Round 2).
--
-- Round 1 (V003) gave catalog.product_customization_groups exactly four columns
-- — the composite key (product_id, group_id) plus `required` and `display_order`
-- — so every other effective value was inherited from the master group and the
-- lock token was the owning Product version (no independent relationship
-- version). The management API declared that honestly through
-- ProductCustomizationCapabilitiesResponse.roundOne() and refused writes that
-- needed a column that did not exist.
--
-- This migration adds the storage Round 1 promised it would:
--   * an independent relationship `version` (capabilities relationshipVersion
--     source becomes RELATIONSHIP, not PRODUCT);
--   * per-product minimum/maximum selection overrides (NULL = inherit master);
--   * a per-product default option;
--   * a Product/Group/Option allow-list table (product_customization_options)
--     with an optional per-product price override;
--   * the standard V048 audit columns and an active/deleted state flag.
--
-- Migration rules honoured (same discipline as V041):
--   * V001 through V048 are NEVER edited. This is a purely additive step.
--   * Idempotent: ADD COLUMN IF NOT EXISTS, CREATE TABLE IF NOT EXISTS,
--     CREATE INDEX IF NOT EXISTS, and drop-then-add CHECK/FK inside DO blocks.
--   * Constraints are applied AFTER a deterministic backfill so existing data
--     is guaranteed to satisfy them the moment they are enabled.
--   * Rules that cannot be expressed as a single-table check — SINGLE capped at
--     max 1, "default option is inside the effective allowed set", "maximum >=
--     the inherited minimum", "price override keeps a valid final price" — are
--     enforced in the application service and covered by tests, exactly as the
--     Round 1 storage contract stipulated. This migration does not fake them.
--
-- Backfill preserves behaviour byte-for-byte: every existing product/group row
-- copies the master group's own minimum/maximum into the (now-overridable)
-- columns, keeps required and display_order, invents NO default, and lists
-- every currently-ACTIVE master option in the product allow-list. POS, Customer
-- catalog, Cart validation and Availability therefore resolve to exactly the
-- same effective configuration after V049 as before it.
-- =============================================================================

-- --------------------------------------------------------------- A. pcg columns
ALTER TABLE catalog.product_customization_groups
    ADD COLUMN IF NOT EXISTS version             bigint      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS minimum_selections  integer     NULL,
    ADD COLUMN IF NOT EXISTS maximum_selections  integer     NULL,
    ADD COLUMN IF NOT EXISTS default_option_id   uuid        NULL,
    ADD COLUMN IF NOT EXISTS active              boolean     NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS created_at          timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at          timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS created_by          uuid        NULL,
    ADD COLUMN IF NOT EXISTS updated_by          uuid        NULL;

COMMENT ON COLUMN catalog.product_customization_groups.version IS
    'Independent optimistic-lock token for the attachment (capabilities relationshipVersionSource = RELATIONSHIP from V049 on).';
COMMENT ON COLUMN catalog.product_customization_groups.minimum_selections IS
    'Per-product override. NULL means inherit the master group minimum.';
COMMENT ON COLUMN catalog.product_customization_groups.maximum_selections IS
    'Per-product override. NULL means inherit the master group maximum (which itself may be NULL = unbounded).';
COMMENT ON COLUMN catalog.product_customization_groups.default_option_id IS
    'Per-product default option. NULL means no default. Must belong to the effective allowed set (enforced in the service).';

-- --------------------------------------------------------------- B. pc options
CREATE TABLE IF NOT EXISTS catalog.product_customization_options (
    product_customization_option_id uuid           PRIMARY KEY,
    product_id                      uuid           NOT NULL,
    group_id                        uuid           NOT NULL,
    option_id                       uuid           NOT NULL,
    allowed                         boolean        NOT NULL DEFAULT true,
    price_override                  numeric(14, 4) NULL,
    display_order                   integer        NOT NULL DEFAULT 0,
    version                         bigint         NOT NULL DEFAULT 0,
    active                          boolean        NOT NULL DEFAULT true,
    created_at                      timestamptz    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      timestamptz    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                      uuid           NULL,
    updated_by                      uuid           NULL,
    CONSTRAINT uk_product_customization_option UNIQUE (product_id, group_id, option_id)
);

COMMENT ON TABLE catalog.product_customization_options IS
    'Product-specific allowed-option subset with optional per-product price override. One row per (product, group, option).';

-- ------------------------------------------------------------------- backfill A
-- Copy the master group's inherited limits into the new override columns for
-- every pre-existing attachment. The `IS NULL` guard makes this idempotent and
-- ensures it never clobbers a row an operator already configured. A default is
-- deliberately NOT invented (default_option_id stays NULL).
UPDATE catalog.product_customization_groups pcg
   SET minimum_selections = cg.minimum_selections,
       maximum_selections = cg.maximum_selections,
       default_option_id  = NULL,
       active             = true,
       updated_at         = CURRENT_TIMESTAMP
  FROM catalog.customization_groups cg
 WHERE pcg.group_id = cg.group_id
   AND pcg.minimum_selections IS NULL
   AND pcg.maximum_selections IS NULL;

-- ------------------------------------------------------------------- backfill B
-- Seed the allow-list with every currently-ACTIVE option of the attached group
-- so the effective configuration is unchanged. Inactive options are NOT added.
-- Deterministic per (product, group, option) via the unique constraint.
INSERT INTO catalog.product_customization_options (
        product_customization_option_id,
        product_id,
        group_id,
        option_id,
        allowed,
        price_override,
        display_order,
        version,
        active
    )
SELECT
        gen_random_uuid(),
        pcg.product_id,
        pcg.group_id,
        co.option_id,
        true,
        NULL,
        co.display_order,
        0,
        true
  FROM catalog.product_customization_groups pcg
  JOIN catalog.customization_options co
    ON co.group_id = pcg.group_id
   AND co.active = true
ON CONFLICT (product_id, group_id, option_id) DO NOTHING;

-- --------------------------------------------------- constraints (after backfill)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_pcg_minimum_non_negative') THEN
        ALTER TABLE catalog.product_customization_groups
            ADD CONSTRAINT ck_pcg_minimum_non_negative
            CHECK (minimum_selections IS NULL OR minimum_selections >= 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_pcg_maximum_non_negative') THEN
        ALTER TABLE catalog.product_customization_groups
            ADD CONSTRAINT ck_pcg_maximum_non_negative
            CHECK (maximum_selections IS NULL OR maximum_selections >= 0);
    END IF;

    -- Only comparable when BOTH overrides are present. A SINGLE-group cap of 1
    -- and "maximum >= the inherited (possibly NULL) minimum" are service rules
    -- because the effective minimum may live on the master group.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_pcg_min_le_max') THEN
        ALTER TABLE catalog.product_customization_groups
            ADD CONSTRAINT ck_pcg_min_le_max
            CHECK (
                minimum_selections IS NULL
                OR maximum_selections IS NULL
                OR maximum_selections >= minimum_selections
            );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pcg_default_option') THEN
        ALTER TABLE catalog.product_customization_groups
            ADD CONSTRAINT fk_pcg_default_option
            FOREIGN KEY (default_option_id)
            REFERENCES catalog.customization_options (option_id)
            ON DELETE RESTRICT;
    END IF;
END $$;

-- pc options: FKs + value checks. Composite FK to the owning attachment means a
-- detach (which deletes the pcg row) also removes its allow-list rows.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pcg_option_attachment') THEN
        ALTER TABLE catalog.product_customization_options
            ADD CONSTRAINT fk_pcg_option_attachment
            FOREIGN KEY (product_id, group_id)
            REFERENCES catalog.product_customization_groups (product_id, group_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pcg_option_option') THEN
        ALTER TABLE catalog.product_customization_options
            ADD CONSTRAINT fk_pcg_option_option
            FOREIGN KEY (option_id)
            REFERENCES catalog.customization_options (option_id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_pcg_option_display_order') THEN
        ALTER TABLE catalog.product_customization_options
            ADD CONSTRAINT ck_pcg_option_display_order
            CHECK (display_order >= 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_pcg_option_price_override') THEN
        ALTER TABLE catalog.product_customization_options
            ADD CONSTRAINT ck_pcg_option_price_override
            CHECK (price_override IS NULL OR price_override >= 0);
    END IF;
END $$;

-- ---------------------------------------------------------------------- indexes
CREATE INDEX IF NOT EXISTS ix_pcg_product_active
    ON catalog.product_customization_groups (product_id, active, display_order);

CREATE INDEX IF NOT EXISTS ix_pcg_default_option
    ON catalog.product_customization_groups (default_option_id)
    WHERE default_option_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_pcg_option_lookup
    ON catalog.product_customization_options (product_id, group_id, active, display_order);

CREATE INDEX IF NOT EXISTS ix_pcg_option_by_option
    ON catalog.product_customization_options (option_id);
