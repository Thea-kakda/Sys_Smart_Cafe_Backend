package com.smartcoffee.modules.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One role assignment as seen by an administrator. {@code storeId} null means a
 * global grant; {@code revokedAt}/{@code expiresAt} make the row's lifecycle
 * explicit so the detail view can show active and historical assignments without
 * the client guessing.
 */
public record StaffAssignmentView(
        UUID assignmentId,
        String roleCode,
        String roleName,
        UUID storeId,
        String storeName,
        Instant assignedAt,
        Instant expiresAt,
        Instant revokedAt,
        boolean active) {
}
