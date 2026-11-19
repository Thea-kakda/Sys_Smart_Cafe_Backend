-- Slice 4B smoke reservation verification for the Smoke Latte line(s).
\pset pager off
-- 1) active reservations for the fixture's cart items at BKK1
SELECT r.status, r.source_type, r.resolution_source, r.demand_revision,
       r.cart_item_id, i.quantity AS item_qty
  FROM inventory.inventory_reservations r
  JOIN cart.cart_items i ON i.cart_item_id = r.cart_item_id
 WHERE r.store_id = '10000000-0000-0000-0000-000000000001'
   AND r.source_recipe_version_id IN ('9c000000-0000-0000-0000-000000000021','9c000000-0000-0000-0000-000000000022');

-- 2) reservation lines per ACTIVE reservation (expected for Large+Oat+50%+Extra qty1:
--    beans 33, milk 0, oat 240, syrup 15, ice 160, cup16 1)
SELECT ing.ingredient_code, l.quantity_reserved
  FROM inventory.inventory_reservation_lines l
  JOIN inventory.inventory_reservations r ON r.reservation_id = l.reservation_id
  JOIN inventory.ingredients ing ON ing.ingredient_id = l.ingredient_id
 WHERE r.store_id = '10000000-0000-0000-0000-000000000001'
   AND r.status = 'ACTIVE'
   AND r.source_recipe_version_id IN ('9c000000-0000-0000-0000-000000000021','9c000000-0000-0000-0000-000000000022')
 ORDER BY ing.ingredient_code;

-- 3) balances: current_stock (should be unchanged at 100000) vs reserved_stock
SELECT ing.ingredient_code, b.current_stock, b.reserved_stock
  FROM inventory.inventory_balances b
  JOIN inventory.ingredients ing ON ing.ingredient_id = b.ingredient_id
 WHERE b.store_id = '10000000-0000-0000-0000-000000000001'
   AND ing.ingredient_id BETWEEN '9c000000-0000-0000-0000-000000000101' AND '9c000000-0000-0000-0000-000000000108'
 ORDER BY ing.ingredient_code;

-- 4) no SALE_CONSUMPTION for this store (must be 0)
SELECT count(*) AS sale_consumption_rows FROM inventory.stock_transactions
 WHERE store_id = '10000000-0000-0000-0000-000000000001' AND transaction_type = 'SALE_CONSUMPTION';

-- 5) reservation-only ledger types written for the fixture recipes
SELECT t.transaction_type, count(*) FROM inventory.stock_transactions t
 WHERE t.store_id = '10000000-0000-0000-0000-000000000001'
 GROUP BY t.transaction_type ORDER BY 1;
