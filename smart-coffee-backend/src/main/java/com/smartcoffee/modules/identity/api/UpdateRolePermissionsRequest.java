package com.smartcoffee.modules.identity.api;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Full replacement of a role's permission set (Master §7.2). The list is authoritative:
 * omitted codes are revoked from the role, present codes are granted. {@code null} is
 * rejected; an empty list is valid only for non-protected roles (removes all grants).
 *
 * <p>No version/ETag field is accepted: identity.roles has no version column, so the
 * update is a transactional replacement rather than an optimistic-lock write. Duplicate
 * or blank entries are normalized in the service; unknown codes are rejected so no new
 * permission can be minted through this endpoint.
 */
public record UpdateRolePermissionsRequest(
        @NotNull List<String> permissionCodes) {
}
