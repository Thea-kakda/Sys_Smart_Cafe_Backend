package com.smartcoffee.modules.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/v1/management/roles (V052, SUPER_ADMIN-only). A custom role
 * is minted EMPTY: permissions are attached afterwards through the existing
 * audited PUT /roles/{roleId}/permissions, never in the same call.
 */
public record CreateRoleRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,59}$", message = "roleCode must match ^[A-Z][A-Z0-9_]{2,59}$")
        String roleCode,

        @NotBlank
        @Size(max = 120)
        String roleName,

        @Size(max = 255)
        String description,

        @NotBlank
        @Pattern(regexp = "^(GLOBAL|STORE)$", message = "roleScope must be GLOBAL or STORE")
        String roleScope) {
}
