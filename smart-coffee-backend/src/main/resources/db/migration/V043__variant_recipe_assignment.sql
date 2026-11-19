-- =============================================================================
-- V043: Enterprise catalog upgrade Slice 2A — variant recipe assignment,
--        one-ACTIVE-per-product enforcement, and recipe management permissions.
-- =============================================================================
-- Depends on V042 (status domain already widened to DRAFT/ACTIVE/SUPERSEDED/
-- ARCHIVED). Nothing here rewrites recipe identity or demand.
--
--   1. catalog.product_variants.active_recipe_version_id — an OPTIONAL explicit
--      override pointer. Nullable, so no existing product/variant is forced to
--      have a recipe and legacy behaviour is preserved.
--   2. One ACTIVE recipe version per product, enforced at the DB with a partial
--      unique index. A preflight DO block fails the migration with an actionable
--      diagnostic if any product currently holds >1 ACTIVE version, instead of
--      silently picking one or surfacing a cryptic index error.
--   3. recipe:view / recipe:manage / recipe:activate / recipe:assign permissions,
--      granted to the same audience as the Slice 1 catalog management codes.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Variant -> ACTIVE recipe version override pointer
-- -----------------------------------------------------------------------------
ALTER TABLE catalog.product_variants
    ADD COLUMN active_recipe_version_id uuid NULL;

ALTER TABLE catalog.product_variants
    ADD CONSTRAINT fk_variant_active_recipe
    FOREIGN KEY (active_recipe_version_id)
    REFERENCES recipe.recipe_versions (recipe_version_id);

-- Lookup index for resolve-by-variant and the "version still assigned?" guard.
CREATE INDEX ix_variant_active_recipe
    ON catalog.product_variants (active_recipe_version_id)
    WHERE active_recipe_version_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 2. One ACTIVE recipe version per product
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    dup_products bigint;
BEGIN
    SELECT count(*) INTO dup_products
    FROM (
        SELECT product_id
        FROM recipe.recipe_versions
        WHERE status = 'ACTIVE'
        GROUP BY product_id
        HAVING count(*) > 1
    ) d;

    IF dup_products > 0 THEN
        RAISE EXCEPTION
            'Slice 2A preflight: % product(s) have more than one ACTIVE recipe version. '
            'Supersede/retire all but the intended active version before applying uk_recipe_one_active_per_product.',
            dup_products;
    END IF;
END $$;

CREATE UNIQUE INDEX uk_recipe_one_active_per_product
    ON recipe.recipe_versions (product_id)
    WHERE status = 'ACTIVE';

-- -----------------------------------------------------------------------------
-- 3. Recipe management permissions
-- -----------------------------------------------------------------------------
INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('f4000000-0000-0000-0000-000000000001', 'recipe:view'),
    ('f4000000-0000-0000-0000-000000000002', 'recipe:manage'),
    ('f4000000-0000-0000-0000-000000000003', 'recipe:activate'),
    ('f4000000-0000-0000-0000-000000000004', 'recipe:assign')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER gains the recipe management codes (mirrors variant:manage).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code IN
    ('recipe:view', 'recipe:manage', 'recipe:activate', 'recipe:assign')
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
