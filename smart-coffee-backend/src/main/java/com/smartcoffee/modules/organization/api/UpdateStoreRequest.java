package com.smartcoffee.modules.organization.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Full-replacement update command (PUT) for a branch profile. {@code storeCode}
 * is immutable business identity and is deliberately absent. {@code version} is
 * required and must equal the current server version, otherwise the update is
 * rejected with 409 to prevent lost writes.
 */
public record UpdateStoreRequest(
        @NotBlank @Size(max = 150) String storeName,
        @NotBlank @Size(max = 2000) String address,
        @Size(max = 30) String phone,
        @NotBlank @Size(max = 64) String timezone,
        @NotBlank @Size(min = 3, max = 3) String defaultCurrency,
        @NotBlank @Size(max = 10) String defaultLanguage,
        @NotNull @PositiveOrZero Long version) {}
