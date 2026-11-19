package com.smartcoffee.modules.identity.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Additive access-context contract for the authenticated user. {@code GET
 * /api/v1/users/me} already returns the basic profile; this endpoint layers on
 * the effective roles, resolved permission codes and the branches the user may
 * actually reach, so frontends never have to trust a locally-stored storeId.
 * It never exposes credentials or token hashes.
 */
public record AccessContextResponse(
        UUID userId,
        String fullName,
        String email,
        String preferredLanguage,
        String status,
        Set<String> roles,
        Set<String> permissions,
        boolean globalAccess,
        List<BranchAccess> accessibleBranches) {

    public record BranchAccess(
            UUID storeId,
            String storeCode,
            String storeName,
            String timezone,
            String defaultCurrency,
            String defaultLanguage,
            boolean active) {}
}
