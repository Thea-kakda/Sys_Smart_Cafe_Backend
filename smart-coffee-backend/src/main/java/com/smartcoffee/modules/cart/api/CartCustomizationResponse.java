package com.smartcoffee.modules.cart.api;

import java.math.BigDecimal;
import java.util.UUID;

public record CartCustomizationResponse(UUID groupId, UUID optionId, String groupName, String optionName, int quantity,
        BigDecimal additionalPrice) {
}

