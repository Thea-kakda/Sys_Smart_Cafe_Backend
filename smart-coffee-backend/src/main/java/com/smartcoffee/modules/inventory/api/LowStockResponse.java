package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One low-stock balance for a store (M2). {@code available} is
 * {@code current - reserved}; a row is returned only when it is at/below its
 * configured {@code reorderPoint}. {@code suggestedQuantity} is
 * {@code maxStock - currentStock} clamped at zero (the reorder-suggestions
 * endpoint populates it; the plain low-stock read may leave it null).
 */
public record LowStockResponse(UUID ingredientId, String ingredientCode, String ingredientName, String baseUnitCode,
        BigDecimal currentStock, BigDecimal reservedStock, BigDecimal availableStock, BigDecimal reorderPoint,
        BigDecimal maxStock, BigDecimal averageUnitCost) {
}
