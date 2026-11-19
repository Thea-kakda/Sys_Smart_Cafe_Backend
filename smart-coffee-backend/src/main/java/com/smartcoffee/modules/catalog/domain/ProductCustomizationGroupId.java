package com.smartcoffee.modules.catalog.domain;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
@Embeddable
public record ProductCustomizationGroupId(UUID productId, UUID groupId) implements Serializable {
    public ProductCustomizationGroupId { if (productId == null || groupId == null) throw new IllegalArgumentException("IDs are required"); }
}

