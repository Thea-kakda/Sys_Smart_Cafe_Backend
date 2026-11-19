package com.smartcoffee.modules.production.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Body for {@code POST /api/v1/management/production-orders/{id}/complete}
 * (Slice 5A.2 §8). Carries actual output, waste, batch metadata and the
 * optimistic-lock version.
 */
public record CompleteProductionOrderRequest(
        long expectedVersion,
        BigDecimal actualGoodOutputQuantity,
        String outputUnit,
        List<WasteEntryDto> wasteEntries,
        LocalDate manufacturedDate,
        LocalDate expiryDate,
        String lotNumber,
        String completionNote) {
}
