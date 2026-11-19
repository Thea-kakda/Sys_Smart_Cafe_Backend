package com.smartcoffee.modules.promotion.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PromotionResponse(UUID promotionId, String code, String name, boolean eligible, BigDecimal discountAmount,
        String currencyCode, String reason) {
}

