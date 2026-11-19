package com.smartcoffee.modules.promotion.api;

/**
 * Explicit status transition request. The target is validated against the
 * allowed transition table, and {@code version} carries the optimistic lock so
 * a stale dashboard form cannot overwrite a fresher state.
 */
public record PromotionStatusRequest(
        String status,
        long version,
        String reason) {
}
