# Backend Step 10: Promotions and Loyalty

Overlay onto Steps 1 to 9.

APIs: POST `/api/v1/promotions/validate`, GET `/api/v1/customers/me/loyalty`.

Promotion validation supports Store, Product, minimum amount, currency, time window, global usage, and per-Customer usage. Capacity is claimed atomically. Redemptions move RESERVED to REDEEMED or RELEASED.

Loyalty has a Customer-unique Account and append-only Ledger. Earn operations deduplicate by source_event_id. Point reservation moves points from available to reserved. Redemption and release are controlled transitions.

Apply CHECKOUT_PROMOTION_LOYALTY_PATCH.md and PAYMENT_LOYALTY_PATCH.md. V009 assumes V001-V008. Verify Flyway history first.
