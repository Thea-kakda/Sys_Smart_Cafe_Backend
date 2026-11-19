package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

public record InventoryBalanceResponse(UUID ingredientId, String ingredientCode, String ingredientName,
        String baseUnitCode, BigDecimal currentStock, BigDecimal reservedStock, BigDecimal availableStock,
        BigDecimal minimumStock, BigDecimal averageUnitCost, boolean lowStock, long version) {
}

