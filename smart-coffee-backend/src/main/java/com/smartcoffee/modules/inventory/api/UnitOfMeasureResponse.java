package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

public record UnitOfMeasureResponse(UUID unitId, String code, String name, String type, int decimalScale,
        BigDecimal conversionFactorToBase, boolean baseUnit) {
}

