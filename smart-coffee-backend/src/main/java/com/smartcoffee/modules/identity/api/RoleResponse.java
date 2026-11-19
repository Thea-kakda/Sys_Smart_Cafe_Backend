package com.smartcoffee.modules.identity.api;

import java.util.List;
import java.util.UUID;

/**
 * Administrative view of a role and its current permission grants (Master §7.1).
 * {@code permissionCodes} is the role's effective grant set as persisted in
 * identity.role_permissions. V052 adds the lifecycle metadata additively:
 * {@code isSystem} marks the migration-seeded immutable roles, {@code roleScope}
 * is GLOBAL/STORE per identity.roles.role_scope, and {@code description} is the
 * free-text column (null when unset).
 */
public record RoleResponse(
        UUID roleId,
        String roleCode,
        String roleName,
        List<String> permissionCodes,
        boolean isSystem,
        String roleScope,
        String description) {
}
