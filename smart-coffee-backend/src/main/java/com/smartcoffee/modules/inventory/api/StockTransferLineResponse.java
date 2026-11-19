package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

public record StockTransferLineResponse(UUID stockTransferLineId, UUID ingredientId, String ingredientCode,
        String ingredientName, String baseUnitCode, BigDecimal quantity, BigDecimal unitCost) {
}
