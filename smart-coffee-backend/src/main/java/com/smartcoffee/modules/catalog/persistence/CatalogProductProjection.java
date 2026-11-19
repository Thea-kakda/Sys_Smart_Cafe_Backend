package com.smartcoffee.modules.catalog.persistence;
import java.math.BigDecimal;
import java.util.UUID;
public interface CatalogProductProjection {
    UUID getProductId(); String getProductCode(); UUID getCategoryId(); String getCategoryName(); String getProductName(); String getDescription();
    String getImageObjectKey(); BigDecimal getPrice(); String getCurrencyCode(); boolean getAvailable(); int getPreparationMinutes(); boolean getCustomizable();
}

