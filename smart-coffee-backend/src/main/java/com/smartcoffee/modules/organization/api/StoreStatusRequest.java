package com.smartcoffee.modules.organization.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Activate/deactivate command. A dedicated command (rather than a field on the
 * profile update) keeps status transitions auditable as their own action and
 * requires the caller's current {@code version} for optimistic locking.
 */
public record StoreStatusRequest(
        @NotNull Boolean active,
        @NotNull @PositiveOrZero Long version) {}
