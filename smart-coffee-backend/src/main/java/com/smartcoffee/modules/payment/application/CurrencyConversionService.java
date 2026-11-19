package com.smartcoffee.modules.payment.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.payment.domain.Currency;
import com.smartcoffee.modules.payment.persistence.CurrencyExchangeRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single authoritative currency-conversion engine (Checkpoint 5B). Both the
 * read-only preview endpoint and the cash-payment settlement path (5C) call
 * {@link #convert}, so a preview and the eventual charge can never disagree on
 * how a rate is selected, inverted or rounded.
 *
 * <p>Selection is deterministic at an instant: a branch-specific rate wins over
 * a global one, then the latest effective window. There is NO silent fallback
 * to 1 for differing currencies — a missing/expired/not-yet-effective rate is a
 * hard {@code RATE_UNAVAILABLE}. Direction is reported honestly: when only the
 * inverse quote exists it is used and flagged {@code inversionUsed}.
 */
@Service
public class CurrencyConversionService {

    /** Documented rounding: half-up to the target currency's minor units. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final CurrencyExchangeRateRepository rates;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public CurrencyConversionService(CurrencyExchangeRateRepository rates, PermissionEvaluator permissions,
            StoreAuthorizationService stores) {
        this.rates = rates;
        this.permissions = permissions;
        this.stores = stores;
    }

    /**
     * Guarded read-only preview (Checkpoint 5B). Authentication is resolved by
     * the controller; here the caller must hold the preview permission and have
     * access to the branch whose rates are being sampled. The preview reflects
     * the rate that WOULD apply now but reserves nothing.
     */
    @Transactional(readOnly = true)
    public ConversionResult preview(CurrentActor actor, UUID storeId, String sourceCurrency, String targetCurrency,
            BigDecimal amount) {
        permissions.require(actor, PermissionCodes.CURRENCY_CONVERSION_PREVIEW);
        stores.requireStore(actor, storeId);
        return convert(storeId, sourceCurrency, targetCurrency, amount, Instant.now());
    }

    public ConversionResult convert(UUID storeId, String sourceCurrency, String targetCurrency, BigDecimal amount,
            Instant at) {
        return convert(storeId, Currency.parse(sourceCurrency), Currency.parse(targetCurrency), amount, at);
    }

    @Transactional(readOnly = true)
    public ConversionResult convert(UUID storeId, Currency from, Currency to, BigDecimal amount, Instant at) {
        if (amount == null) {
            throw new ValidationException("AMOUNT_REQUIRED", "An amount is required");
        }
        if (amount.signum() < 0) {
            throw new ValidationException("NEGATIVE_AMOUNT", "Amount must not be negative");
        }
        Instant moment = at == null ? Instant.now() : at;

        // Same-currency is a documented identity conversion, not a rate lookup.
        if (from == to) {
            BigDecimal same = amount.setScale(from.minorUnits(), ROUNDING);
            return new ConversionResult(same, from, same, to, null, BigDecimal.ONE, from, to, false, "IDENTITY",
                    moment, null, ROUNDING, to.minorUnits(), same.subtract(amount));
        }

        var candidates = rates.findCandidates(storeId, from, to, moment);
        if (candidates.isEmpty()) {
            throw new ConflictException("RATE_UNAVAILABLE",
                    "No applicable exchange rate for " + from + " -> " + to + " at this time");
        }
        var chosen = candidates.get(0);
        var conversion = chosen.conversionFor(from, to);
        if (conversion == null) {
            // Defensive: the query only returns rows matching one direction.
            throw new ConflictException("RATE_UNAVAILABLE", "Selected rate does not cover the requested pair");
        }

        int scale = to.minorUnits();
        // Compute the exact (unrounded) target at high intermediate precision so
        // the reported rounding residual is honest, then apply the documented
        // HALF_UP rule to the target currency's minor units.
        BigDecimal raw = conversion.inverted()
                ? amount.divide(chosen.rate(), scale + 12, ROUNDING)
                : amount.multiply(chosen.rate());
        BigDecimal target = raw.setScale(scale, ROUNDING);
        BigDecimal rounding = target.subtract(raw).setScale(10, RoundingMode.HALF_UP);

        return new ConversionResult(amount.setScale(from.minorUnits(), ROUNDING), from, target, to, chosen.id(),
                chosen.rate(), chosen.baseCurrency(), chosen.quoteCurrency(), conversion.inverted(), chosen.source(),
                chosen.effectiveFrom(), chosen.effectiveTo(), ROUNDING, scale, rounding);
    }

    /**
     * Result of an authoritative conversion. Carries enough metadata for a
     * preview response and for an immutable payment snapshot (5D).
     */
    public record ConversionResult(
            BigDecimal sourceAmount,
            Currency sourceCurrency,
            BigDecimal targetAmount,
            Currency targetCurrency,
            UUID exchangeRateId,
            BigDecimal appliedRate,
            Currency baseCurrency,
            Currency quoteCurrency,
            boolean inversionUsed,
            String rateSource,
            Instant effectiveAt,
            Instant validUntil,
            RoundingMode roundingMode,
            int targetScale,
            /** Residual introduced by rounding the exact target to minor units (target currency). */
            BigDecimal roundingAmount) {
    }
}
