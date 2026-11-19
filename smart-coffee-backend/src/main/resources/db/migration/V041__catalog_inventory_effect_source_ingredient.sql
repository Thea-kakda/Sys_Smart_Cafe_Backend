-- =============================================================================
-- V041: Reshape option inventory-effect columns to model source vs target
-- ingredient explicitly.
--
-- Context (Slice 1A semantic review): V039 used a single column,
-- target_ingredient_id, as "the ingredient affected by the option" for every
-- effect type and discovered the REPLACE source through a self-FK
-- replaces_option_id. That conflates two different concepts and forces an
-- option-to-option coupling for milk substitution that the business model does
-- not require.
--
-- Preferred domain model, adopted here:
--   ADD      : target_ingredient_id required, source_ingredient_id NULL.
--   REMOVE   : source_ingredient_id required, target_ingredient_id NULL,
--              effect_quantity optional (when NULL the full calculated source
--              amount is removed).
--   REPLACE  : both source and target required and must differ;
--              effect_quantity optional (when NULL the full calculated source
--              amount is preserved and re-added under the target).
--   MULTIPLY : source_ingredient_id required, target_ingredient_id NULL,
--              effect_multiplier required and non-negative.
--   NONE     : all auxiliary fields NULL.
--
-- Migration rules honoured:
--   * V039 is NOT edited (already applied to shared/dev databases; recorded in
--     flyway_schema_history as success).
--   * The replaces_option_id column and FK remain in place so any historical
--     row keeps parsing; the new ck_option_effect_shape simply requires a
--     source_ingredient_id for REPLACE. Effectively deprecates replaces_option_id
--     for new writes.
--   * No product or option rows are updated: as of V039 every existing
--     customization_option defaulted to inventory_effect_type='NONE' with all
--     auxiliary columns NULL. Backfill is a no-op for the current data set and
--     remains deterministic for any environment that populated REPLACE before
--     this migration (see DO block below).
--   * Idempotent via IF NOT EXISTS + drop-then-add CHECK.
-- =============================================================================

ALTER TABLE catalog.customization_options
    ADD COLUMN IF NOT EXISTS source_ingredient_id uuid NULL;

-- Optional FK, added only when it does not already exist.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_option_source_ingredient'
    ) THEN
        ALTER TABLE catalog.customization_options
            ADD CONSTRAINT fk_option_source_ingredient
            FOREIGN KEY (source_ingredient_id)
            REFERENCES inventory.ingredients(ingredient_id)
            ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_options_source_ingredient
    ON catalog.customization_options (source_ingredient_id)
    WHERE source_ingredient_id IS NOT NULL;

-- Deterministic backfill for any environment that populated REMOVE/MULTIPLY
-- under the V039 shape (target = the ingredient being reduced/scaled). For
-- those two types, "affected ingredient" is unambiguously the source under the
-- new model; the column is copied and target is cleared. REPLACE cannot be
-- backfilled from V039 data (replaces_option_id points at another option, not
-- an ingredient) — such rows remain invalid until an operator supplies
-- source_ingredient_id explicitly, and the CHECK below enforces that.
UPDATE catalog.customization_options
   SET source_ingredient_id = target_ingredient_id,
       target_ingredient_id = NULL
 WHERE inventory_effect_type IN ('REMOVE', 'MULTIPLY')
   AND source_ingredient_id IS NULL
   AND target_ingredient_id IS NOT NULL;

-- Reshape the CHECK. Drop + re-add is safe because V039 shipped the constraint
-- under the same name and no data currently depends on the old shape.
ALTER TABLE catalog.customization_options
    DROP CONSTRAINT IF EXISTS ck_option_effect_shape;
ALTER TABLE catalog.customization_options
    ADD CONSTRAINT ck_option_effect_shape CHECK (
        (inventory_effect_type = 'NONE'
            AND source_ingredient_id IS NULL
            AND target_ingredient_id IS NULL
            AND effect_quantity IS NULL
            AND effect_multiplier IS NULL)
        OR (inventory_effect_type = 'ADD'
            AND target_ingredient_id IS NOT NULL
            AND effect_quantity IS NOT NULL
            AND effect_quantity > 0
            AND source_ingredient_id IS NULL
            AND effect_multiplier IS NULL)
        OR (inventory_effect_type = 'REMOVE'
            AND source_ingredient_id IS NOT NULL
            AND target_ingredient_id IS NULL
            AND effect_multiplier IS NULL
            AND (effect_quantity IS NULL OR effect_quantity > 0))
        OR (inventory_effect_type = 'REPLACE'
            AND source_ingredient_id IS NOT NULL
            AND target_ingredient_id IS NOT NULL
            AND source_ingredient_id <> target_ingredient_id
            AND effect_multiplier IS NULL
            AND (effect_quantity IS NULL OR effect_quantity > 0))
        OR (inventory_effect_type = 'MULTIPLY'
            AND source_ingredient_id IS NOT NULL
            AND target_ingredient_id IS NULL
            AND effect_multiplier IS NOT NULL
            AND effect_multiplier >= 0
            AND effect_quantity IS NULL)
    );
