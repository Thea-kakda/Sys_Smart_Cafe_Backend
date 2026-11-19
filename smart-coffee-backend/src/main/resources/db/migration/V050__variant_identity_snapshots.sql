-- =============================================================================
-- V050: Variant identity snapshots on cart and order lines (Slice 4B.1).
--
-- The live Slice 4B smoke proved the cart priced and displayed lines from the
-- product/store base while reservations correctly used the selected variant:
-- variant_id existed on both cart_items (V045) and order_items (V046), but no
-- variant code/name/price was ever persisted. A cart created yesterday against
-- "Large 16 oz" could only be labeled by joining catalog.product_variants at
-- read time, so a rename or price edit silently rewrote history — the same
-- integrity problem the product_*_snapshot columns solved for products.
--
-- Persistence is therefore necessary, not projection sugar:
--   * §5 of the slice contract requires the cart to survive a later variant
--     rename/price change without a live join;
--   * §7 requires the converted order to keep the variant identity it sold.
-- variant_id itself already exists and is reused untouched.
--
-- Migration rules honoured (same discipline as V049):
--   * V001 through V049 are NEVER edited. This step is purely additive.
--   * Idempotent: ADD COLUMN IF NOT EXISTS; the backfill UPDATEs are guarded
--     so a re-run changes nothing.
--   * All new columns are NULLable: product-level lines carry no variant, and
--     pre-V050 rows are honestly null until the backfill below labels them.
--   * No new constraints: snapshot columns are display/pricing history, and
--     referential truth stays with catalog (writes are validated in the
--     CartCatalogGateway with stable errors instead).
--
-- Backfill: existing lines that already carry a variant_id copy the current
-- catalog variant code/name/price. This is the best available reconstruction
-- for historical rows (the catalog value is the only one we ever stored); new
-- lines always snapshot at write time and never read these catalog columns
-- again.
-- =============================================================================

-- ------------------------------------------------------------ A. cart lines
ALTER TABLE cart.cart_items
    ADD COLUMN IF NOT EXISTS variant_code_snapshot  varchar(80)   NULL,
    ADD COLUMN IF NOT EXISTS variant_name_snapshot  varchar(150)  NULL,
    ADD COLUMN IF NOT EXISTS variant_price_snapshot numeric(14,4) NULL;

COMMENT ON COLUMN cart.cart_items.variant_code_snapshot IS
    'Variant SKU as selected at add/configure time (Slice 4B.1). NULL for product-level lines.';
COMMENT ON COLUMN cart.cart_items.variant_name_snapshot IS
    'Variant label as selected at add/configure time; display must never resolve through a live catalog join.';
COMMENT ON COLUMN cart.cart_items.variant_price_snapshot IS
    'Authoritative variant base price captured when the line was written; later catalog edits do not change it.';

UPDATE cart.cart_items ci
   SET variant_code_snapshot  = v.variant_code,
       variant_name_snapshot  = v.variant_name,
       variant_price_snapshot = v.price
  FROM catalog.product_variants v
 WHERE ci.variant_id = v.variant_id
   AND ci.variant_name_snapshot IS NULL;

-- ------------------------------------------------------------ B. order lines
ALTER TABLE ordering.order_items
    ADD COLUMN IF NOT EXISTS variant_code_snapshot  varchar(80)   NULL,
    ADD COLUMN IF NOT EXISTS variant_name_snapshot  varchar(150)  NULL,
    ADD COLUMN IF NOT EXISTS variant_price_snapshot numeric(14,4) NULL;

COMMENT ON COLUMN ordering.order_items.variant_code_snapshot IS
    'Variant SKU sold, carried from the cart line snapshot at checkout (Slice 4B.1). NULL for product-level lines.';
COMMENT ON COLUMN ordering.order_items.variant_name_snapshot IS
    'Variant label sold; historical orders stay readable after any later variant rename.';
COMMENT ON COLUMN ordering.order_items.variant_price_snapshot IS
    'Variant base price sold; equals unit_base_price''s provenance for variant lines (option deltas add on top).';

UPDATE ordering.order_items oi
   SET variant_code_snapshot  = v.variant_code,
       variant_name_snapshot  = v.variant_name,
       variant_price_snapshot = v.price
  FROM catalog.product_variants v
 WHERE oi.variant_id = v.variant_id
   AND oi.variant_name_snapshot IS NULL;
