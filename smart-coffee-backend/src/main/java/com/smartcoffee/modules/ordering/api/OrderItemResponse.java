package com.smartcoffee.modules.ordering.api;

import java.math.BigDecimal;
import java.util.*;

public record OrderItemResponse(UUID orderItemId, UUID productId, String productName, int quantity,
        BigDecimal lineSubtotal, BigDecimal lineDiscountAmount, BigDecimal lineTaxableAmount, BigDecimal lineTaxAmount,
        BigDecimal lineTotal, List<String> customizations) {
}
