package com.smartcoffee.modules.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Staff creation command (Master Prompt §6.3). Deliberately does NOT accept a
 * security-version, status or a pre-computed role assignment id: those are derived
 * server-side. {@code roleCode}/{@code storeId} drive the single assignment made
 * at creation; the rule "non-admin role requires a store, global only for admin
 * roles" is enforced authoritatively in the service, not here. Business-format
 * checks (email uniqueness, role/store existence) are likewise server-side.
 */
public record CreateStaffRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 10, max = 128) String password,
        @Size(max = 30) String phone,
        @NotBlank @Pattern(regexp = "^(en|km|en-US|km-KH)$",
                message = "preferredLanguage must be one of 'en', 'km', 'en-US', 'km-KH'") String preferredLanguage,
        @NotBlank @Size(max = 50) String roleCode,
        UUID storeId,
        Instant expiresAt) {
}
