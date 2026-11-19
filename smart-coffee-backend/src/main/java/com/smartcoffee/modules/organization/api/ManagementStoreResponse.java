package com.smartcoffee.modules.organization.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Protected management view of a branch (technical term: Store). Unlike the
 * public {@link StoreResponse}, this contract is for administrators: it never
 * fabricates an {@code open} flag and it exposes the optimistic-lock
 * {@code version} so the client can supply it back on update.
 */
public record ManagementStoreResponse(
        UUID storeId,
        String storeCode,
        String storeName,
        String address,
        String phone,
        String timezone,
        String defaultCurrency,
        String defaultLanguage,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
