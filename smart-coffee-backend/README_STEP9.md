# Backend Step 9: Production Tickets and Station Queues

Overlay onto Steps 1 to 8. Apply ORDER_PRODUCTION_PATCH.md and PAYMENT_INTEGRATION_PATCH.md.

APIs: GET `/api/v1/stores/{storeId}/production/{barista|kitchen|bakery}`, POST `/api/v1/production/ticket-items/{id}/start`, POST `/api/v1/production/ticket-items/{id}/ready`. Mutations require Idempotency-Key.

Production tickets are split by station. First production start consumes the Order Inventory Reservation and writes the SALE_CONSUMPTION ledger. All station tickets READY transitions the Order to READY. Events are written through Outbox and compact realtime updates are sent.

> Superseded by Slice 2D (see [ADR-007](adr/ADR-007-reservation-conversion.md)): production start no longer touches inventory. The order's reservation is consumed when the payment settles, and checkout converts the cart's persisted hold instead of releasing and re-reserving.

V008 assumes V001-V007. Verify Flyway history before applying.
