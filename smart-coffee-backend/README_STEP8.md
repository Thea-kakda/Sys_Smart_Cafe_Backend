# Backend Step 8: Payment, Cash, KHQR and Receipt

Overlay onto Steps 1 to 7. Apply ORDER_DOMAIN_PATCH.md.

APIs: POST `/api/v1/payments/cash`, POST `/api/v1/payments/khqr`, POST `/api/v1/payments/{paymentId}/verify`, GET `/api/v1/payment-methods`.

Failure policy: a failed attempt is preserved while Order stays AWAITING_PAYMENT for retry. On timeout, Payment becomes EXPIRED, unpaid Order is cancelled, and active Inventory Reservation is released. Failed attempts never consume Inventory.

Cash: backend calculates settlement amount and change. KHQR: provider result is authoritative. One PAID Payment per Order is enforced by a partial unique index.

The production KHQR adapter from Step 1 remains a placeholder and must be replaced with a verified provider implementation and callback signature validation.

V007 assumes V001-V006. Verify Flyway history before first application.
