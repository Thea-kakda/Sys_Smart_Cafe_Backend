package com.smartcoffee.modules.identity.api;

import java.util.UUID;

/**
 * Administrative view of a single permission (Master §7.1). {@code category} is
 * derived safely from the code prefix before the first colon (e.g. "staff" for
 * "staff:read"); it is null when the code has no such prefix. No description is
 * supplied because identity.permissions stores none — fabricating one is forbidden.
 */
public record PermissionResponse(
        UUID permissionId,
        String permissionCode,
        String category) {
}
