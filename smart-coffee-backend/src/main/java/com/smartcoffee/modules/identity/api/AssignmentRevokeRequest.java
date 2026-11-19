package com.smartcoffee.modules.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Revoke-a-role-assignment command (Master Prompt §6.6). The target assignment is
 * identified by the path variable, not the body. An assignment has no version
 * column, so no optimistic-lock token is accepted here; revoking bumps the user's
 * security_version and is audited with the supplied {@code reason}.
 */
public record AssignmentRevokeRequest(
        @NotBlank @Size(max = 300) String reason) {
}
