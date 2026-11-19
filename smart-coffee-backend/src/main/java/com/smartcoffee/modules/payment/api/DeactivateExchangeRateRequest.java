package com.smartcoffee.modules.payment.api;

import jakarta.validation.constraints.NotNull;

/**
 * Deactivation command (Checkpoint 10). A rate is never physically deleted —
 * deactivation preserves history while removing the row from future selection.
 * The optimistic-lock {@code version} is required so a stale UI cannot silently
 * retire a rate another operator has since re-opened or superseded.
 */
public record DeactivateExchangeRateRequest(@NotNull Long version) {
}
