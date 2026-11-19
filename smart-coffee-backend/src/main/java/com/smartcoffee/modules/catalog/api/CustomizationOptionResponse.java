package com.smartcoffee.modules.catalog.api;
import java.math.BigDecimal;
import java.util.UUID;
public record CustomizationOptionResponse(UUID optionId,String code,String name,BigDecimal additionalPrice,boolean available,
        /* Slice 4B additive: per-product default, sourced ONLY from EffectiveCustomizationResolver (never inferred). */
        boolean isDefault) {}

