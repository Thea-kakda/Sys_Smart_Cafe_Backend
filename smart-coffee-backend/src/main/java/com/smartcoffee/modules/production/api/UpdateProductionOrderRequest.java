package com.smartcoffee.modules.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Edits a DRAFT Production Order (Slice 5A §23). Allowed only while status = DRAFT. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateProductionOrderRequest(
        long expectedVersion,
        BigDecimal plannedOutputQuantity,
        UUID plannedOutputUnitId,
        Instant scheduledFor,
        String notes) {
}
