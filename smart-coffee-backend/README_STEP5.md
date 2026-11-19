# Backend Step 5: Customer Cart and Strict Customization Validation

Overlay this ZIP onto the merged Step 1 to Step 4 backend.

## APIs

- `GET /api/v1/carts/current?storeId={storeId}`
- `POST /api/v1/carts/current/items`
- `PATCH /api/v1/carts/{cartId}/items/{itemId}`
- `DELETE /api/v1/carts/{cartId}/items/{itemId}`
- `DELETE /api/v1/carts/{cartId}/items`

## Validation

- Product must be active and offered by the selected Store.
- Store Product must be available and not temporarily sold out.
- Requested options must belong to Product groups.
- Required groups must meet minimum selections.
- Maximum selections are enforced.
- SINGLE groups reject multiple distinct options.
- Inactive or unknown options are rejected.
- Duplicate options and non-positive quantities are rejected.
- Client prices are never accepted.

## Pricing

Cart snapshots Store-effective base and customization prices. Step 6 checkout must reload Catalog prices and reject or reconcile changes before creating an Order.

## Migration

`V004` assumes the previous overlays use V001 to V003. Inspect real Flyway history first and never rename an applied migration.

## Known scope

Promotion, tax, Loyalty, Delivery fees, and checkout Inventory Reservation intentionally remain outside Step 5. Response fields are present as zero until those modules are integrated.
