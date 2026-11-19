package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record InventoryBalanceId(UUID storeId, UUID ingredientId) implements Serializable {
}

