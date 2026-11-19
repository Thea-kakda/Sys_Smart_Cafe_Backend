package com.smartcoffee.modules.payment.api;

import com.smartcoffee.modules.payment.domain.CurrencyExchangeRate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Management view of an exchange-rate row (Checkpoint 10). Exposes the full
 * provenance and lifecycle of a rate — direction (base/quote/rate), source, the
 * effective window, whether it is still active, its scope and the optimistic-lock
 * {@code version} — so an operator can reason about exactly which quote the
 * conversion engine would select. {@code storeId} is null for a GLOBAL rate;
 * {@code scope} is the derived, display-friendly form.
 */
public record ExchangeRateResponse(
        UUID exchangeRateId,
        UUID storeId,
        String scope,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        String source,
        Instant effectiveFrom,
        Instant effectiveTo,
        boolean active,
        UUID createdByUserId,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static ExchangeRateResponse from(CurrencyExchangeRate r) {
        return new ExchangeRateResponse(r.id(), r.storeId(), r.isGlobal() ? "GLOBAL" : "BRANCH",
                r.baseCurrency().name(), r.quoteCurrency().name(), r.rate(), r.source(), r.effectiveFrom(),
                r.effectiveTo(), r.active(), r.createdByUserId(), r.createdAt(), r.updatedAt(), r.version());
    }
}
