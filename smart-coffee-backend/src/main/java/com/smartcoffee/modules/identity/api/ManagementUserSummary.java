package com.smartcoffee.modules.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the protected User Directory (Full-stack wiring §1.1). Covers ALL
 * users — staff, customers, or both — which is why it lives beside, and
 * deliberately NOT as a clone of, {@link StaffResponse}: a staff view assumes a
 * role assignment exists, a directory row often does not. {@code roles} lists
 * the codes of currently-active assignments (empty for pure customers) and
 * {@code customer} is the presence flag for a customer profile. No credential
 * material is ever carried — same invariant as the staff contract.
 */
public record ManagementUserSummary(
        UUID userId,
        String fullName,
        String email,
        String phone,
        String preferredLanguage,
        String status,
        List<String> roles,
        boolean customer,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
