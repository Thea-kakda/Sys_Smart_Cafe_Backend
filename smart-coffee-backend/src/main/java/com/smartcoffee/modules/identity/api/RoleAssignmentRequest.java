package com.smartcoffee.modules.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Add-a-role-assignment command (Master Prompt §6.6). {@code storeId} null requests
 * a global grant; the service rejects a global grant for any non-admin role and a
 * store-scoped grant for an admin role, enforces role/store existence, forbids a
 * duplicate active assignment, and requires a future {@code expiresAt} when given.
 */
public record RoleAssignmentRequest(
        @NotBlank @Size(max = 50) String roleCode,
        UUID storeId,
        Instant expiresAt) {
}
