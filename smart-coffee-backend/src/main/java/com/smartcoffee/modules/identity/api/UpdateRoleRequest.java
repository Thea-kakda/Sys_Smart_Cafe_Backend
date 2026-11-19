package com.smartcoffee.modules.identity.api;

import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /api/v1/management/roles/{roleId} (V052). Only role_name and
 * description are mutable; role_code, role_scope and the system flag never
 * change after creation. At least one field must be present (service-checked).
 */
public record UpdateRoleRequest(
        @Size(max = 120)
        String roleName,

        @Size(max = 255)
        String description) {
}
