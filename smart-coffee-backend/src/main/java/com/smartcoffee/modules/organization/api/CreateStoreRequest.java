package com.smartcoffee.modules.organization.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create command for a branch. No {@code version} is accepted: creation always
 * establishes version 0 server-side. Business-format checks (IANA timezone,
 * supported currency/language, phone) are enforced authoritatively in
 * {@code StoreManagementService}; the annotations here are the safe client layer.
 */
public record CreateStoreRequest(
        @NotBlank @Size(max = 30) String storeCode,
        @NotBlank @Size(max = 150) String storeName,
        @NotBlank @Size(max = 2000) String address,
        @Size(max = 30) String phone,
        @NotBlank @Size(max = 64) String timezone,
        @NotBlank @Size(min = 3, max = 3) String defaultCurrency,
        @NotBlank @Size(max = 10) String defaultLanguage,
        Boolean active) {

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
