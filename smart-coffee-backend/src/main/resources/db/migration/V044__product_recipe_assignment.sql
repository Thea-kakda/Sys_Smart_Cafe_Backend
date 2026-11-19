-- =============================================================================
-- V044: Slice 2B.1 — recipe assignment architecture correction.
--
-- The Slice 2A model overloaded RecipeVersion.status = ACTIVE as BOTH a
-- lifecycle state AND the unique product-fallback selector (partial unique
-- index uk_recipe_one_active_per_product, V043). That makes it impossible to
-- hold a distinct Product fallback recipe and ProductVariant override recipe
-- at the same time. This migration separates the two concepts:
--
--   * lifecycle: ACTIVE is reinterpreted (without renaming) as
--     "published, immutable and assignable"; several assignable versions may
--     now coexist per product. DRAFT stays editable, ARCHIVED stays retired.
--   * selection: explicit pointer columns decide which version is used —
--       catalog.products.active_recipe_version_id        (fallback)
--       catalog.product_variants.active_recipe_version_id (override, V043)
--
-- Smallest safe forward change only. No applied migration is edited; V043's
-- index is dropped here by its repository-controlled name (verified in
-- V043__variant_recipe_assignment.sql, line: CREATE UNIQUE INDEX
-- uk_recipe_one_active_per_product).
--
-- Hard guarantees:
--   * Existing recipe version / line IDs and quantities are untouched.
--   * Existing product / variant IDs and prices are untouched.
--   * Backfill copies the (at most one, by V043) ACTIVE version of each
--     product into the new product pointer, so checkout demand via the
--     pointer-based resolver is byte-for-byte unchanged for legacy data.
--   * Products without any ACTIVE version keep a NULL pointer (never
--     fabricated) and remain sellable.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Preflight: every existing variant override pointer must reference a
--    recipe version of the variant's OWN product. Slice 2A enforced this in
--    the application layer; fail loudly instead of carrying bad data across
--    the constraint change.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    bad bigint;
BEGIN
    SELECT count(*) INTO bad
    FROM catalog.product_variants v
    JOIN recipe.recipe_versions rv ON rv.recipe_version_id = v.active_recipe_version_id
    WHERE v.active_recipe_version_id IS NOT NULL
      AND rv.product_id <> v.product_id;

    IF bad > 0 THEN
        RAISE EXCEPTION
            'RECIPE_ASSIGNMENT_PREFLIGHT_FAILED: % variant(s) point at a recipe version owned by a FOREIGN-PRODUCT. '
            'Fix the pointers before applying V044.', bad;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 2. Product fallback pointer (explicit assignment, mirrors V043's variant one)
-- -----------------------------------------------------------------------------
ALTER TABLE catalog.products
    ADD COLUMN active_recipe_version_id uuid NULL;

ALTER TABLE catalog.products
    ADD CONSTRAINT fk_product_active_recipe
    FOREIGN KEY (active_recipe_version_id)
    REFERENCES recipe.recipe_versions (recipe_version_id);

CREATE INDEX ix_product_active_recipe
    ON catalog.products (active_recipe_version_id)
    WHERE active_recipe_version_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 3. Backfill: reproduce the pre-V044 selection. Before this migration a
--    product's fallback recipe WAS its single ACTIVE version (uk index in
--    V043 guarantees at most one). Do not touch products without one, and do
--    not bump updated_at/version — this is a representational move, not a
--    business edit; optimistic-lock baselines of live rows stay valid.
-- -----------------------------------------------------------------------------
UPDATE catalog.products p
SET active_recipe_version_id = rv.recipe_version_id
FROM recipe.recipe_versions rv
WHERE rv.product_id = p.product_id
  AND rv.status = 'ACTIVE';

-- -----------------------------------------------------------------------------
-- 4. Drop the incompatible selector constraint. Assignment (pointers), not
--    status, now chooses the effective recipe; multiple assignable (ACTIVE)
--    versions may coexist per product (e.g. product fallback + variant
--    override as two DIFFERENT versions).
-- -----------------------------------------------------------------------------
DROP INDEX recipe.uk_recipe_one_active_per_product;
