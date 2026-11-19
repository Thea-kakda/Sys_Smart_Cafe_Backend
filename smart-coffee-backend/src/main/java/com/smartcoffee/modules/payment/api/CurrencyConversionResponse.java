package com.smartcoffee.modules.payment.api;

import com.smartcoffee.modules.payment.application.CurrencyConversionService.ConversionResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response of a currency conversion preview (Checkpoint 5B). Exposes the full
 * decision trail — the rate row used, its direction, whether it was inverted,
 * its provenance and validity window, and the documented rounding — so the
 * client shows exactly what the backend computed and nothing is hidden. It is a
 * quote only: it does not reserve the rate or guarantee it for a future payment.
 */
public record CurrencyConversionResponse(
        BigDecimal sourceAmount,
        String sourceCurrency,
        BigDecimal targetAmount,
        String targetCurrency,
        UUID exchangeRateId,
        BigDecimal appliedRate,
        String baseCurrency,
        String quoteCurrency,
        boolean inversionUsed,
        String rateSource,
        Instant effectiveAt,
        Instant validUntil,
        String roundingMode,
        int targetScale,
        Instant serverTimestamp) {

    public static CurrencyConversionResponse from(ConversionResult r, Instant serverTimestamp) {
        return new CurrencyConversionResponse(r.sourceAmount(), r.sourceCurrency().name(), r.targetAmount(),
                r.targetCurrency().name(), r.exchangeRateId(), r.appliedRate(), r.baseCurrency().name(),
                r.quoteCurrency().name(), r.inversionUsed(), r.rateSource(), r.effectiveAt(), r.validUntil(),
                r.roundingMode().name(), r.targetScale(), serverTimestamp);
    }
}
