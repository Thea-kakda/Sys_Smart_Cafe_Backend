package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creates a supplier master record. Field lengths mirror the V033 schema (code
 * &lt;=50, name &lt;=150). {@code active} is optional and defaults to true.
 */
public record CreateSupplierRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 150) String contactName,
        @Size(max = 30) String phone,
        @Email @Size(max = 150) String email,
        @Size(max = 1000) String address,
        @Size(max = 50) String taxId,
        Boolean active) {

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }
}
