package com.smartcoffee.modules.catalog.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape for the protected branch-product management read contract
 * (Checkpoint 3A). All values are authoritative backend columns or
 * backend-computed expressions; the browser never recomputes them.
 */
public interface ManagementStoreProductProjection {
    UUID getProductId();

    String getProductCode();

    String getProductName();

    UUID getCategoryId();

    String getCategoryName();

    BigDecimal getBasePrice();

    BigDecimal getOverridePrice();

    BigDecimal getEffectivePrice();

    String getCurrencyCode();

    Boolean getProductActive();

    Boolean getBranchAvailable();

    Boolean getEffectiveAvailable();

    Instant getSoldOutUntil();

    String getStationCode();

    Integer getPreparationMinutes();

    Boolean getCustomizable();

    Long getProductVersion();

    Long getStoreProductVersion();

    Instant getUpdatedAt();
}
