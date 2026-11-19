package com.smartcoffee.modules.production.api;

/**
 * Body for {@code POST /api/v1/management/production-orders/{id}/close}
 * (Slice 5A.2 §10). Only COMPLETED orders may close; no inventory movement.
 */
public record CloseProductionOrderRequest(
        long expectedVersion,
        String note) {
}
