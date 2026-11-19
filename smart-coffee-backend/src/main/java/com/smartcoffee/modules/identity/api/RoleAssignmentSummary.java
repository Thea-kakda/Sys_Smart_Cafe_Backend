package com.smartcoffee.modules.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of GET /api/v1/management/roles/{roleId}/assignments (V052). Carries
 * ids, status and scope only — no email/phone/password, so the role:assignment-
 * read capability cannot become a PII side channel.
 */
public record RoleAssignmentSummary(
        UUID assignmentId,
        UUID userId,
        String userStatus,
        UUID storeId,
        Instant assignedAt,
        Instant expiresAt,
        Instant revokedAt) {
}
