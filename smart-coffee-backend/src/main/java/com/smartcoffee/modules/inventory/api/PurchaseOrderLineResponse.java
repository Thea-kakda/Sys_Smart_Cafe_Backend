package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderLineResponse(UUID lineId, UUID ingredientId, String ingredientCode, String ingredientName,
        String unitCode, BigDecimal quantityOrdered, BigDecimal quantityReceived, BigDecimal outstanding,
        BigDecimal unitCost) {
}
