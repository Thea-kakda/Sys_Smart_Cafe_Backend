package com.smartcoffee.modules.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Create command for an exchange rate (Checkpoint 10). The client supplies only
 * intent — the pair, the quote, its scope and effective window. No {@code
 * version} is accepted: a new row always establishes version 0 server-side, and
 * any currently-open predecessor for the same directed pair/scope is superseded
 * authoritatively by the service. Currency-code format, sign, precision and
 * window ordering are re-validated by the domain model, never trusted here.
 */
public record CreateExchangeRateRequest(
        /** null storeId records a GLOBAL rate; a non-null id is branch-scoped. */
        UUID storeId,
        @NotBlank @Size(min = 3, max = 3) String baseCurrency,
        @NotBlank @Size(min = 3, max = 3) String quoteCurrency,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal rate,
        @Size(max = 40) String source,
        /** Optional start; the server defaults it to "now" when omitted. */
        Instant effectiveFrom,
        /** Optional exclusive end; null means the rate stays open until superseded. */
        Instant effectiveTo) {
}
