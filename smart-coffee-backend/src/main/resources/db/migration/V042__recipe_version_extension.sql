-- =============================================================================
-- V042: Enterprise catalog upgrade Slice 2A — extend the EXISTING product-scoped
--        recipe model in place. No new recipe master table, no line duplication.
-- =============================================================================
-- This migration evolves recipe.recipe_versions and recipe.recipe_version_lines
-- so the authoritative checkout recipe source (used by RecipeDemandService) gains
-- lifecycle breadth, yield metadata, per-line unit/scaling/display/optional data
-- and audit columns WITHOUT changing any existing identity or demand value.
--
-- Hard compatibility guarantees enforced here:
--   * Existing recipe_version_id / recipe_version_line_id values are untouched.
--   * Existing quantity_required_base / wastage_percent values are untouched.
--   * ordering.order_items.recipe_version_id FK is unaffected (no PK change).
--   * No recipes are created for products that have none.
--   * The optimistic-lock `version` column already exists and is correct, so it
--     is only newly mapped in the entity (no DDL change).
--
-- Backfill rationale (verified against repository evidence):
--   * yield_quantity = 1: RecipeVersionLine.demand(qty) computes
--     quantity_required_base * qty, i.e. each persisted version describes exactly
--     ONE sellable unit of its product. A neutral yield of 1 preserves checkout
--     demand byte-for-byte and is therefore safe to backfill NOT NULL.
--   * yield_unit_id stays NULL: we must not guess an incompatible yield unit for
--     historical rows (spec: "Do not guess incompatible yield units"). New drafts
--     set it explicitly through the application.
--   * scaling_rule = FIXED: existing demand is per-unit and independent of yield,
--     which is exactly FIXED semantics (with yield 1, FIXED and
--     PROPORTIONAL_TO_YIELD coincide, so behaviour is identical either way).
--   * optional = false and display_order backfilled deterministically by
--     (created_at, line id) preserve current ordering and demand.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- recipe.recipe_versions — lifecycle + yield + audit
-- -----------------------------------------------------------------------------

-- 1. Expand the status domain. Drop the narrow CHECK first so the RETIRED ->
--    ARCHIVED normalization below does not violate it, then re-add the widened
--    CHECK. The four target states are DRAFT / ACTIVE / SUPERSEDED / ARCHIVED.
ALTER TABLE recipe.recipe_versions
    DROP CONSTRAINT ck_recipe_status;

UPDATE recipe.recipe_versions
SET status = 'ARCHIVED'
WHERE status = 'RETIRED';

ALTER TABLE recipe.recipe_versions
    ADD CONSTRAINT ck_recipe_status
    CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED', 'ARCHIVED'));

-- 2. Yield + free-text notes. yield_quantity is decimal-safe and defaults to 1
--    (see rationale above); a CHECK forbids non-positive yield.
ALTER TABLE recipe.recipe_versions
    ADD COLUMN yield_quantity numeric(18,4) NOT NULL DEFAULT 1,
    ADD COLUMN yield_unit_id  uuid NULL,
    ADD COLUMN notes          text NULL;

ALTER TABLE recipe.recipe_versions
    ADD CONSTRAINT ck_recipe_version_yield
    CHECK (yield_quantity IS NULL OR yield_quantity > 0);

ALTER TABLE recipe.recipe_versions
    ADD CONSTRAINT fk_recipe_version_yield_unit
    FOREIGN KEY (yield_unit_id) REFERENCES inventory.units_of_measure (unit_id);

-- 3. Activation + created/updated audit. These are nullable actor references so
--    historical rows (created before this slice) remain valid. updated_at is
--    backfilled from created_at for existing rows to preserve original timing.
ALTER TABLE recipe.recipe_versions
    ADD COLUMN activated_at  timestamp with time zone NULL,
    ADD COLUMN activated_by  uuid NULL,
    ADD COLUMN created_by    uuid NULL,
    ADD COLUMN updated_by    uuid NULL,
    ADD COLUMN updated_at    timestamp with time zone NOT NULL DEFAULT now();

-- existing rows; the ALTER DEFAULT filled updated_at with now(), so we restore
-- it to the original created_at for every pre-existing version.
UPDATE recipe.recipe_versions
SET updated_at = created_at;

CREATE INDEX ix_recipe_version_yield_unit
    ON recipe.recipe_versions (yield_unit_id)
    WHERE yield_unit_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- recipe.recipe_version_lines — unit + scaling + display + optional + notes
-- -----------------------------------------------------------------------------

ALTER TABLE recipe.recipe_version_lines
    ADD COLUMN unit_id      uuid NULL,
    ADD COLUMN scaling_rule varchar(30) NOT NULL DEFAULT 'FIXED',
    ADD COLUMN display_order integer NOT NULL DEFAULT 0,
    ADD COLUMN optional     boolean NOT NULL DEFAULT false,
    ADD COLUMN notes        text NULL;

ALTER TABLE recipe.recipe_version_lines
    ADD CONSTRAINT ck_recipe_line_scaling
    CHECK (scaling_rule IN ('FIXED', 'PROPORTIONAL_TO_YIELD'));

ALTER TABLE recipe.recipe_version_lines
    ADD CONSTRAINT fk_recipe_line_unit
    FOREIGN KEY (unit_id) REFERENCES inventory.units_of_measure (unit_id);

-- Deterministic display_order backfill: 0-based sequence per version ordered by
-- the stable line id (the table has no created_at column). Line ids and demand
-- values are left untouched.
WITH ordered AS (
    SELECT recipe_version_line_id,
           row_number() OVER (
               PARTITION BY recipe_version_id
               ORDER BY recipe_version_line_id
           ) - 1 AS rn
    FROM recipe.recipe_version_lines
)
UPDATE recipe.recipe_version_lines l
SET display_order = o.rn
FROM ordered o
WHERE l.recipe_version_line_id = o.recipe_version_line_id;

CREATE INDEX ix_recipe_line_unit
    ON recipe.recipe_version_lines (unit_id)
    WHERE unit_id IS NOT NULL;

CREATE INDEX ix_recipe_line_version_display
    ON recipe.recipe_version_lines (recipe_version_id, display_order);
