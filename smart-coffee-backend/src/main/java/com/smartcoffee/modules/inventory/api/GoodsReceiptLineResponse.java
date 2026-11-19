package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record GoodsReceiptLineResponse(UUID lineId, UUID ingredientId, String ingredientCode, String ingredientName,
        BigDecimal quantity, BigDecimal unitCost, String lotNumber, LocalDate expiryDate) {
}
