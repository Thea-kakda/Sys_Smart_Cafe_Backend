package com.smartcoffee.modules.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Staff activate/disable command (Master Prompt §6.5). Lock/unlock is intentionally
 * not exposed here: the domain has no admin-driven lock (login lockout is
 * automatic and time-boxed), so only the ACTIVE / DISABLED transition is offered.
 * A {@code reason} is mandatory for the audit trail and {@code version} provides
 * optimistic locking. Disabling a staff member also bumps their security_version
 * and revokes their refresh sessions (service-side).
 */
public record StaffStatusRequest(
        @NotNull Boolean active,
        @NotBlank @Size(max = 300) String reason,
        @NotNull(message = "version is required") Long version) {
}
