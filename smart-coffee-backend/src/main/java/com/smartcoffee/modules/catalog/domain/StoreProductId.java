package com.smartcoffee.modules.catalog.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record StoreProductId(UUID storeId, UUID productId) implements Serializable {
    public StoreProductId {
        if (storeId == null || productId == null)
            throw new IllegalArgumentException("IDs are required");
    }
}

