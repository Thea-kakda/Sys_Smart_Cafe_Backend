package com.smartcoffee.modules.production.api;

/**
 * Body for {@code POST /api/v1/management/production-orders/{id}/cancel}
 * (Slice 5A.2 §9). Reason is required; no inventory movement occurs.
 */
public record CancelProductionOrderRequest(
        long expectedVersion,
        String reason) {
}
