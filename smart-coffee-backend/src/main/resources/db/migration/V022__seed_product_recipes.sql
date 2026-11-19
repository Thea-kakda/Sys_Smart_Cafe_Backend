-- ============================================================
-- V022: Seed product recipes for the demo catalog
-- ------------------------------------------------------------
-- V005 seeded the ingredients (Coffee Bean, Fresh Milk,
-- Croissant Unit) and their BKK1 inventory balances, but the
-- matching recipe_versions / recipe_version_lines were never
-- inserted. Checkout computes ingredient demand from a product's
-- ACTIVE recipe to place an inventory reservation, so without a
-- recipe every checkout failed with "Active recipe not found" and
-- the POS/Customer-Web purchase path could not complete.
--
-- This restores the clearly-intended data: the latte consumes
-- coffee + milk; the croissant consumes one croissant unit. All
-- demand stays far below the seeded stock (5000 / 10000 / 100).
-- Deterministic ids keep the seed reproducible and idempotent.
-- ============================================================

INSERT INTO recipe.recipe_versions (
    recipe_version_id,
    product_id,
    version_number,
    status,
    effective_from,
    effective_to
)
VALUES
    (
        '81000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000001', -- ICED_LATTE
        1,
        'ACTIVE',
        '2024-01-01T00:00:00Z',
        NULL
    ),
    (
        '81000000-0000-0000-0000-000000000002',
        '30000000-0000-0000-0000-000000000002', -- CROISSANT
        1,
        'ACTIVE',
        '2024-01-01T00:00:00Z',
        NULL
    ) ON CONFLICT (product_id, version_number) DO NOTHING;

INSERT INTO recipe.recipe_version_lines (
    recipe_version_line_id,
    recipe_version_id,
    ingredient_id,
    quantity_required_base,
    wastage_percent
)
VALUES
    (
        '82000000-0000-0000-0000-000000000001',
        '81000000-0000-0000-0000-000000000001',
        '70000000-0000-0000-0000-000000000001', -- COFFEE_BEAN
        18,
        0
    ),
    (
        '82000000-0000-0000-0000-000000000002',
        '81000000-0000-0000-0000-000000000001',
        '70000000-0000-0000-0000-000000000002', -- FRESH_MILK
        200,
        0
    ),
    (
        '82000000-0000-0000-0000-000000000003',
        '81000000-0000-0000-0000-000000000002',
        '70000000-0000-0000-0000-000000000003', -- CROISSANT_UNIT
        1,
        0
    ) ON CONFLICT (recipe_version_id, ingredient_id) DO NOTHING;
