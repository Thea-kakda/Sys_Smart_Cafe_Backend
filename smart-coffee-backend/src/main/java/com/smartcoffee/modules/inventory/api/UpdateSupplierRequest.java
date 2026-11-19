package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Full-replacement supplier update. Carries the optimistic-lock {@code version}
 * echoed from the last read; a stale value maps to {@code 409 VERSION_CONFLICT}
 * (catalog convention). Setting {@code active=false} deactivates (no hard delete).
 */
public record UpdateSupplierRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 150) String contactName,
        @Size(max = 30) String phone,
        @Email @Size(max = 150) String email,
        @Size(max = 1000) String address,
        @Size(max = 50) String taxId,
        Boolean active,
        @NotNull @PositiveOrZero Long version) {

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }
}
