package com.smartcoffee.modules.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Fields the caller supplies when opening a DRAFT Production Order (Slice 5A
 * §22). The server generates the {@code productionOrderNumber} and never
 * trusts a client-provided value (spec §13). Optional fields default to null
 * and are validated by the command service before the aggregate is opened.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateProductionOrderRequest(
        UUID storeId,
        UUID productId,
        UUID variantId,
        BigDecimal plannedOutputQuantity,
        UUID plannedOutputUnitId,
        Instant scheduledFor,
        String notes) {
}
