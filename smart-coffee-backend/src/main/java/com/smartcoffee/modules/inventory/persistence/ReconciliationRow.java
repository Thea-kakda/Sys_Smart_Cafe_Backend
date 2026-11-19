package com.smartcoffee.modules.inventory.persistence;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single raw row produced by one of the bounded reconciliation detectors
 * (Slice 2E). Which fields are populated depends on the detector; the service
 * turns a row into a typed {@link com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFinding}.
 *
 * @param storeId   owning store (always present)
 * @param itemId    inventory item, when the anomaly is item-scoped
 * @param reservationId the offending reservation, when one is named
 * @param orderId   related order, when applicable
 * @param cartId    related cart, when applicable
 * @param expected  the derived/authoritative quantity (e.g. SUM of ACTIVE lines)
 * @param actual    the recorded quantity (e.g. balance reserved_stock)
 */
public record ReconciliationRow(
        UUID storeId,
        UUID itemId,
        UUID reservationId,
        UUID orderId,
        UUID cartId,
        BigDecimal expected,
        BigDecimal actual) {
}
