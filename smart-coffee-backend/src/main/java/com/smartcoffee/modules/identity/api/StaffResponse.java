package com.smartcoffee.modules.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Administrative view of a staff member (Master Prompt §6.1/§6.2). Combines the
 * safe identity profile with the caller-visible role assignments. It NEVER
 * carries password hashes, refresh tokens, security-version or any credential
 * material. {@code roles} lists the codes of currently-active assignments;
 * {@code assignments} is the full active + historical view used by the detail
 * endpoint (the list endpoint supplies active rows only).
 */
public record StaffResponse(
        UUID userId,
        String fullName,
        String email,
        String phone,
        String preferredLanguage,
        String status,
        List<String> roles,
        List<StaffAssignmentView> assignments,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
