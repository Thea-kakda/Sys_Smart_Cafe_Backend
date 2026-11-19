# Backend Step 7: Order Checkout and Inventory Reservation Integration

Overlay onto the merged Step 1 to Step 6 project.

## Endpoints
- POST `/api/v1/orders` with `Idempotency-Key`
- GET `/api/v1/orders/{orderId}`

## Atomic checkout
Cart lock, Catalog revalidation, immutable snapshots, Order creation, Recipe demand, Inventory reservation, Cart checkout, Outbox event, and Idempotency completion run in one PostgreSQL transaction.

## Required patch
Apply `CART_DOMAIN_PATCH.md` to Step 5 `Cart.java`.

## Current limits
Tax rate is currently read as zero by the Step 5 Cart catalog gateway because Step 5 did not expose Product tax metadata. Before production, extend `CartCatalogGateway.ProductOffer` and `JpaCartCatalogGateway` to include Product taxRate and Store stationCode. The Step 7 adapter is designed for these values but defaults station to `UNASSIGNED` and tax to zero until that patch is made.

Payment, Promotion, Loyalty, and Production Ticket generation remain later steps.
