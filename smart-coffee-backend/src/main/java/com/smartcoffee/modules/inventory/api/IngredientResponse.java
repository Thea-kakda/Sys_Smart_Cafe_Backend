package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IngredientResponse(UUID ingredientId, String code, String name, UUID baseUnitId, String baseUnitCode,
        String baseUnitName, BigDecimal standardCost, boolean trackInventory, boolean trackBatches,
        Integer shelfLifeDays, boolean active, Instant createdAt, Instant updatedAt, long version) {
}
