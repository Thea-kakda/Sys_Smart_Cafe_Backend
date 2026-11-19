# Backend Step 6: Recipe, Inventory, UOM, Reservation and Ledger

Overlay onto Steps 1 to 5. UOM quantities are normalized into each Ingredient base unit before Recipe, Balance, Reservation, or Ledger persistence. KILOGRAM converts to GRAM and LITER converts to MILLILITER. Unit types must match the Ingredient base unit type.

Implemented: Units, Ingredients, versioned Recipes, Recipe lines, Balances, atomic reserve SQL, Reservation header/lines, Release, Consume, Expire scheduler, consumption Ledger, Inventory query, UOM query, migration, and concurrency-test skeleton.

Endpoints: `GET /api/v1/units-of-measure`, `GET /api/v1/stores/{storeId}/inventory`. Reservation APIs remain internal services.

Migration V005 assumes prior V001-V004. Verify Flyway history before first application.
