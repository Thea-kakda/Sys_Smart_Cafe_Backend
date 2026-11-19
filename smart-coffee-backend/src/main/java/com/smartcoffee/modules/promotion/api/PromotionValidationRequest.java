package com.smartcoffee.modules.promotion.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public record PromotionValidationRequest(@NotNull UUID storeId, @NotNull UUID customerId, @NotNull UUID orderId,
        String promotionCode, @NotNull @DecimalMin("0.0000") BigDecimal subtotal, @NotBlank String currencyCode,
        List<UUID> productIds, List<UUID> categoryIds) {
}

