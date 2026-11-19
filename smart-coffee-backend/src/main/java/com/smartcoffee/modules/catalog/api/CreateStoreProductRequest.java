package com.smartcoffee.modules.catalog.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Adds a product to a branch by creating the first catalog.store_products row
 * (Checkpoint 3B). No version field is accepted: creation is guarded by the
 * (store_id, product_id) primary key and duplicate rows map to 409
 * CONFIGURATION_EXISTS. Station is mandatory so new configurations never rely
 * on the legacy production-routing fallback.
 */
public record CreateStoreProductRequest(
        @NotNull UUID productId,
        BigDecimal overridePrice,
        @NotNull Boolean available,
        Instant soldOutUntil,
        @NotNull @Size(min = 1, max = 40) String stationCode) {

    public boolean availableOrDefault() {
        return !Boolean.FALSE.equals(available);
    }
}
