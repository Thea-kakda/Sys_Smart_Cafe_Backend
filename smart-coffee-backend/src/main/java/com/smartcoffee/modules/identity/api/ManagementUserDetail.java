package com.smartcoffee.modules.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Directory detail for one user: the summary header, the FULL role-assignment
 * history (active + revoked + expired, mirroring the staff detail), and the
 * customer-profile slice when the user is also a customer. All three blocks are
 * independently empty-by-data, never empty-by-permission: holding
 * user:admin-read grants the whole view.
 */
public record ManagementUserDetail(
        ManagementUserSummary user,
        List<StaffAssignmentView> assignments,
        CustomerProfileView customerProfile) {

    /**
     * Customer-module profile fields joined for the directory detail. Null
     * loyaltyNumber/preferredStore are legitimate "unset" values; birthDate and
     * marketingOptIn are surfaced exactly as stored (self-service profile data).
     */
    public record CustomerProfileView(
            UUID customerId,
            String loyaltyNumber,
            UUID preferredStoreId,
            String preferredStoreName,
            java.time.LocalDate birthDate,
            boolean marketingOptIn,
            Instant customerSince) {
    }
}
