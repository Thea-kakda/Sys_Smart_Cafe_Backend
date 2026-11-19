-- =============================================================================
-- V036: Reorder planning columns + ingredient batch-tracking master columns (M2)
-- =============================================================================
-- Two additive concerns:
--
--  1) Low-stock / reorder planning on inventory.inventory_balances:
--       reorder_point  — available stock at/below which replenishment is advised
--       max_stock      — target ceiling used to size a reorder suggestion
--     Both default 0; reorder_point is backfilled from the existing
--     minimum_stock so long-standing planning thresholds keep working. A
--     partial index accelerates the per-store low-stock scan.
--
--  2) Ingredient master options on inventory.ingredients (used by M3 batch/FEFO
--     and the ingredient editor):
--       track_batches   — opt-in per ingredient; untracked ingredients and all
--                         existing behaviour are unaffected (default false)
--       shelf_life_days — default shelf life for batches created on receipt
--
-- Additive + idempotent (ADD COLUMN IF NOT EXISTS). The backfill UPDATE only
-- lifts placeholder zeroes, so it is safe and never overwrites a real plan.
-- =============================================================================

ALTER TABLE inventory.inventory_balances
    ADD COLUMN IF NOT EXISTS reorder_point numeric(18, 4) NOT NULL DEFAULT 0;
ALTER TABLE inventory.inventory_balances
    ADD COLUMN IF NOT EXISTS max_stock numeric(18, 4) NOT NULL DEFAULT 0;

ALTER TABLE inventory.inventory_balances
    ADD CONSTRAINT ck_balance_reorder CHECK (reorder_point >= 0);
ALTER TABLE inventory.inventory_balances
    ADD CONSTRAINT ck_balance_max CHECK (max_stock >= 0);

-- Seed reorder_point from the legacy minimum threshold for rows still at the
-- placeholder zero (guarded so a deliberately-set reorder_point is preserved).
UPDATE inventory.inventory_balances
SET reorder_point = minimum_stock
WHERE reorder_point = 0
  AND minimum_stock > 0;

ALTER TABLE inventory.ingredients
    ADD COLUMN IF NOT EXISTS track_batches boolean NOT NULL DEFAULT false;
ALTER TABLE inventory.ingredients
    ADD COLUMN IF NOT EXISTS shelf_life_days integer;

CREATE INDEX IF NOT EXISTS ix_low_stock
    ON inventory.inventory_balances (store_id)
    WHERE current_stock - reserved_stock <= reorder_point;
