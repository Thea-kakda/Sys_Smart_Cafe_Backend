package com.smartcoffee.modules.catalog.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Full-replacement branch-product configuration update (Checkpoint 3B).
 * Null semantics are explicit and documented: {@code overridePrice} null means
 * inherit the base price; {@code soldOutUntil} null clears a temporary sold-out
 * window. {@code available} and {@code stationCode} are required, so omitted
 * fields can never silently reset production routing or availability.
 * {@code storeProductVersion} must echo the current row version; stale values
 * map to 409 VERSION_CONFLICT.
 */
public record UpdateStoreProductConfigurationRequest(
        BigDecimal overridePrice,
        @NotNull Boolean available,
        Instant soldOutUntil,
        @NotNull @Size(min = 1, max = 40) String stationCode,
        @NotNull @PositiveOrZero Long storeProductVersion) {
}
