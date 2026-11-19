package com.smartcoffee.modules.identity.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Administrative profile update (Master Prompt §6.4). Only the safe profile
 * fields are editable here; email changes require the verified email-change flow,
 * and roles/status are managed only through their explicit endpoints, so this DTO
 * deliberately exposes none of them. {@code version} is required to enforce
 * optimistic locking.
 */
public record UpdateStaffRequest(
        @Size(max = 150) String fullName,
        @Pattern(regexp = "^\\+855\\d{8,9}$",
                message = "phone must match +855 followed by 8 or 9 digits") String phone,
        @Pattern(regexp = "^(en|km|en-US|km-KH)$",
                message = "preferredLanguage must be one of 'en', 'km', 'en-US', 'km-KH'") String preferredLanguage,
        @NotNull(message = "version is required") Long version) {
}
