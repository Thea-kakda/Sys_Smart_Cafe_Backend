# Backend Step 4: Catalog, Categories, Store Products, Dynamic Customizations

Overlay this ZIP onto the merged Step 1, Step 2, and Step 3 backend.

## Endpoints

- `GET /api/v1/categories`
- `GET /api/v1/stores/{storeId}/catalog`
- `GET /api/v1/stores/{storeId}/catalog/products/{productId}`
- `GET /api/v1/products/{productId}?storeId={storeId}`

## Query parameters

Catalog listing supports `search`, `categoryId`, `availableOnly`, `page`, and `size`. Page size is bounded to 100.

## Backend authority

- Effective price uses Store override if present, otherwise Product base price.
- Store availability and sold-out time are authoritative.
- Customization groups and options are data-driven.
- The current Product response does not calculate a final customized price.

## Migration warning

`V003` assumes Steps 1 to 3 use `V001` and `V002`. Inspect `flyway_schema_history` before applying it. Never edit an applied migration.

## Seed warning

Sample Coffee, Bakery, Iced Latte, Croissant, Milk, and Sugar rows are development seed data. Move environment-specific production catalog data to an approved seeding workflow.

## Next step

Step 5 should implement Cart with strict customization validation and backend-authoritative totals.
