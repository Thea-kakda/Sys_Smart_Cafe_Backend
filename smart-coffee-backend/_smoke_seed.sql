-- =============================================================================
-- Slice 4B live-POS-smoke FIXTURE (dev DB data only; NO code changes).
-- Creates one dedicated MADE_TO_ORDER Product "Smoke Latte" at store BKK1 with:
--   * Variants REGULAR (default) + LARGE 16oz, each with an ACTIVE recipe
--   * MILK   required  (SINGLE) default=FRESH_MILK, allow-list {Fresh,Oat}, decoy Almond hidden
--   * SUGAR  required  (SINGLE) NO default, allow-list {100%,50%}, decoy 0% hidden
--   * EXTRAS optional  (MULTIPLE) allow-list {Extra Shot}
--   * Option effects: Oat REPLACE milk->oat, Sugar50 MULTIPLY syrup x0.5, Extra ADD beans 9
-- Reservation demand for Large + Oat + Sugar50 + Extra (qty 1):
--   beans 24+9=33, milk 0, oat 240, syrup 30*0.5=15, ice 160, cup16 1  (product recipe keeps 12oz cup/lid at 0)
-- Fixed 9c.. UUIDs so the smoke can query + clean up precisely.
-- =============================================================================
BEGIN;

-- ------------------------------------------------------------------ ingredients
INSERT INTO inventory.ingredients
  (ingredient_id, ingredient_code, ingredient_name, base_unit_id, standard_cost, track_inventory, track_batches, active, created_at, updated_at, version)
VALUES
  ('9c000000-0000-0000-0000-000000000101','SMOKE_BEANS','Smoke Coffee Beans','60000000-0000-0000-0000-000000000001',0,true,false,true,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000102','SMOKE_MILK','Smoke Fresh Milk','60000000-0000-0000-0000-000000000003',0,true,false,true,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000103','SMOKE_SYRUP','Smoke Sugar Syrup','60000000-0000-0000-0000-000000000003',0,true,false,true,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000104','SMOKE_OAT','Smoke Oat Milk','60000000-0000-0000-0000-000000000003',0,true,false,true,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000105','SMOKE_ICE','Smoke Ice','60000000-0000-0000-0000-000000000001',0,true,false,true,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000106','SMOKE_CUP12','Smoke Cup 12oz','60000000-0000-0000-0000-000000000005',0,true,false,true,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000107','SMOKE_LID12','Smoke Lid 12oz','60000000-0000-0000-0000-000000000005',0,true,false,true,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000108','SMOKE_CUP16','Smoke Cup 16oz','60000000-0000-0000-0000-000000000005',0,true,false,true,now(),now(),0)
ON CONFLICT (ingredient_id) DO NOTHING;

-- balances at BKK1 (ample on hand so every option/variant is available)
INSERT INTO inventory.inventory_balances
  (store_id, ingredient_id, current_stock, reserved_stock, minimum_stock, average_unit_cost, updated_at, version)
SELECT '10000000-0000-0000-0000-000000000001', i.ingredient_id, 100000, 0, 0, 0.05, now(), 0
  FROM inventory.ingredients i
 WHERE i.ingredient_id BETWEEN '9c000000-0000-0000-0000-000000000101' AND '9c000000-0000-0000-0000-000000000108'
ON CONFLICT (store_id, ingredient_id) DO NOTHING;

-- --------------------------------------------------------------------- product
INSERT INTO catalog.products
  (product_id, product_code, category_id, product_name, description, base_price, currency_code,
   preparation_minutes, tax_rate, customizable, active, product_type, stock_mode, created_at, updated_at, version)
VALUES
  ('9c000000-0000-0000-0000-000000000001','SMOKE_LATTE','20000000-0000-0000-0000-000000000001',
   'Smoke Latte','Slice 4B live smoke fixture',3.5000,'USD',5,0.10,true,true,'STANDARD','MADE_TO_ORDER',now(),now(),0)
ON CONFLICT (product_id) DO NOTHING;

-- offer at BKK1 + POS channel visibility
INSERT INTO catalog.store_products (store_id, product_id, available, updated_at, version)
VALUES ('10000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000001',true,now(),0)
ON CONFLICT (store_id, product_id) DO NOTHING;

INSERT INTO catalog.product_channels
  (product_channel_id, store_id, product_id, channel, visible, orderable, display_name, display_order, created_at, updated_at, version)
VALUES
  ('9c000000-0000-0000-0000-0000000000c1','10000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000001','POS',true,true,'Smoke Latte',0,now(),now(),0)
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------- variants
INSERT INTO catalog.product_variants
  (variant_id, product_id, variant_code, variant_name, price, currency_code, is_default, active, display_order, created_at, updated_at, version)
VALUES
  ('9c000000-0000-0000-0000-000000000011','9c000000-0000-0000-0000-000000000001','REGULAR','Regular 12 oz',3.5000,'USD',true,true,0,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000012','9c000000-0000-0000-0000-000000000001','LARGE','Large 16 oz',4.5000,'USD',false,true,1,now(),now(),0)
ON CONFLICT (variant_id) DO NOTHING;

-- ------------------------------------------------------------- recipes (active)
-- product fallback recipe = 12oz; Large variant recipe = 16oz (own cup)
INSERT INTO recipe.recipe_versions
  (recipe_version_id, product_id, version_number, status, effective_from, yield_quantity, created_at, updated_at, version, activated_at)
VALUES
  ('9c000000-0000-0000-0000-000000000021','9c000000-0000-0000-0000-000000000001',1,'ACTIVE',now(),1,now(),now(),0,now()),
  ('9c000000-0000-0000-0000-000000000022','9c000000-0000-0000-0000-000000000001',2,'ACTIVE',now(),1,now(),now(),0,now())
ON CONFLICT (recipe_version_id) DO NOTHING;

INSERT INTO recipe.recipe_version_lines (recipe_version_line_id, recipe_version_id, ingredient_id, quantity_required_base, wastage_percent, unit_id, scaling_rule, display_order, optional)
VALUES
  -- 12oz (product fallback): beans18 milk180 syrup20 ice120 cup12 lid12
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000021','9c000000-0000-0000-0000-000000000101',18,0,'60000000-0000-0000-0000-000000000001','FIXED',0,false),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000021','9c000000-0000-0000-0000-000000000102',180,0,'60000000-0000-0000-0000-000000000003','FIXED',1,false),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000021','9c000000-0000-0000-0000-000000000103',20,0,'60000000-0000-0000-0000-000000000003','FIXED',2,false),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000021','9c000000-0000-0000-0000-000000000105',120,0,'60000000-0000-0000-0000-000000000001','FIXED',3,false),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000021','9c000000-0000-0000-0000-000000000106',1,0,'60000000-0000-0000-0000-000000000005','FIXED',4,false),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000021','9c000000-0000-0000-0000-000000000107',1,0,'60000000-0000-0000-0000-000000000005','FIXED',5,false),
  -- 16oz (Large variant): beans24 milk240 syrup30 ice160 cup16
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000022','9c000000-0000-0000-0000-000000000101',24,0,'60000000-0000-0000-0000-000000000001','FIXED',0,false),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000022','9c000000-0000-0000-0000-000000000102',240,0,'60000000-0000-0000-0000-000000000003','FIXED',1,false),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000022','9c000000-0000-0000-0000-000000000103',30,0,'60000000-0000-0000-0000-000000000003','FIXED',2,false),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000022','9c000000-0000-0000-0000-000000000105',160,0,'60000000-0000-0000-0000-000000000001','FIXED',3,false),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000022','9c000000-0000-0000-0000-000000000108',1,0,'60000000-0000-0000-0000-000000000005','FIXED',4,false);

-- link active recipes
UPDATE catalog.products SET active_recipe_version_id='9c000000-0000-0000-0000-000000000021' WHERE product_id='9c000000-0000-0000-0000-000000000001';
UPDATE catalog.product_variants SET active_recipe_version_id='9c000000-0000-0000-0000-000000000022' WHERE variant_id='9c000000-0000-0000-0000-000000000012';
UPDATE catalog.product_variants SET active_recipe_version_id='9c000000-0000-0000-0000-000000000021' WHERE variant_id='9c000000-0000-0000-0000-000000000011';

-- ----------------------------------------------------- customization master data
INSERT INTO catalog.customization_groups (group_id, group_code, group_name, selection_type, minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version)
VALUES
  ('9c000000-0000-0000-0000-000000000201','SMOKE_MILK','Milk','SINGLE',1,1,0,true,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000202','SMOKE_SUGAR','Sugar','SINGLE',1,1,1,true,now(),now(),0),
  ('9c000000-0000-0000-0000-000000000203','SMOKE_EXTRAS','Extras','MULTIPLE',0,null,2,true,now(),now(),0)
ON CONFLICT (group_id) DO NOTHING;

INSERT INTO catalog.customization_options
  (option_id, group_id, option_code, option_name, additional_price, display_order, active, created_at, updated_at, version,
   inventory_effect_type, target_ingredient_id, effect_quantity, effect_multiplier, replaces_option_id, source_ingredient_id)
VALUES
  -- MILK
  ('9c000000-0000-0000-0000-000000000301','9c000000-0000-0000-0000-000000000201','SMOKE_FRESH','Fresh Milk',0.0000,0,true,now(),now(),0,'NONE',null,null,null,null,null),
  ('9c000000-0000-0000-0000-000000000302','9c000000-0000-0000-0000-000000000201','SMOKE_OAT','Oat Milk',0.5000,1,true,now(),now(),0,'REPLACE','9c000000-0000-0000-0000-000000000104',null,null,null,'9c000000-0000-0000-0000-000000000102'),
  ('9c000000-0000-0000-0000-000000000303','9c000000-0000-0000-0000-000000000201','SMOKE_ALMOND','Almond Milk',0.7500,2,true,now(),now(),0,'NONE',null,null,null,null,null), -- decoy: NOT in allow-list
  -- SUGAR
  ('9c000000-0000-0000-0000-000000000311','9c000000-0000-0000-0000-000000000202','SMOKE_SUG100','100%',0.0000,0,true,now(),now(),0,'NONE',null,null,null,null,null),
  ('9c000000-0000-0000-0000-000000000312','9c000000-0000-0000-0000-000000000202','SMOKE_SUG50','50%',0.0000,1,true,now(),now(),0,'MULTIPLY',null,null,0.50,null,'9c000000-0000-0000-0000-000000000103'),
  ('9c000000-0000-0000-0000-000000000313','9c000000-0000-0000-0000-000000000202','SMOKE_SUG0','0%',0.0000,2,true,now(),now(),0,'NONE',null,null,null,null,null), -- decoy: NOT in allow-list
  -- EXTRAS
  ('9c000000-0000-0000-0000-000000000321','9c000000-0000-0000-0000-000000000203','SMOKE_EXTRASHOT','Extra Shot',0.6000,0,true,now(),now(),0,'ADD','9c000000-0000-0000-0000-000000000101',9,null,null,null)
ON CONFLICT (option_id) DO NOTHING;

-- attach groups to the product (V049 attachment: required, default, inherit min/max)
INSERT INTO catalog.product_customization_groups
  (product_id, group_id, required, display_order, version, minimum_selections, maximum_selections, default_option_id, active, created_at, updated_at)
VALUES
  ('9c000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000201',true,0,0,null,null,'9c000000-0000-0000-0000-000000000301',true,now(),now()), -- MILK required, default Fresh
  ('9c000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000202',true,1,0,null,null,null,true,now(),now()),                                 -- SUGAR required, NO default
  ('9c000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000203',false,2,0,null,null,null,true,now(),now())                                -- EXTRAS optional
ON CONFLICT (product_id, group_id) DO NOTHING;

-- product-specific ALLOW-LIST subset (decoys deliberately omitted -> hidden)
INSERT INTO catalog.product_customization_options (product_customization_option_id, product_id, group_id, option_id, allowed, price_override, display_order, version, active)
VALUES
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000201','9c000000-0000-0000-0000-000000000301',true,null,0,0,true),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000201','9c000000-0000-0000-0000-000000000302',true,null,1,0,true),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000202','9c000000-0000-0000-0000-000000000311',true,null,0,0,true),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000202','9c000000-0000-0000-0000-000000000312',true,null,1,0,true),
  (gen_random_uuid(),'9c000000-0000-0000-0000-000000000001','9c000000-0000-0000-0000-000000000203','9c000000-0000-0000-0000-000000000321',true,null,0,0,true);

COMMIT;
