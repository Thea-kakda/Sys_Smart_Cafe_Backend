package com.smartcoffee.modules.payment.domain;

import com.smartcoffee.common.exception.ValidationException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative currency exchange rate (Checkpoint 5A). One row records a
 * fixed-direction quote: {@code 1 baseCurrency = rate quoteCurrency} (e.g. USD
 * -> KHR = 4100). A {@code null} storeId is a GLOBAL rate; a branch-specific
 * rate overrides the global one for that branch (selection precedence is
 * enforced by the conversion service, Checkpoint 5B).
 *
 * <p>Historical rows are retained and never physically deleted once used; a
 * rate change is expressed by closing the old row ({@code effectiveTo}) and
 * inserting a new one, so a past payment can always be reconstructed from its
 * snapshot (Checkpoint 5D). Optimistic locking guards concurrent edits.
 */
@Entity
@Table(schema = "payment", name = "currency_exchange_rates")
public class CurrencyExchangeRate {

    @Id
    @Column(name = "exchange_rate_id")
    private UUID id;
    @Column(name = "store_id")
    private UUID storeId;
    @Enumerated(EnumType.STRING)
    @Column(name = "base_currency", nullable = false, length = 3)
    private Currency baseCurrency;
    @Enumerated(EnumType.STRING)
    @Column(name = "quote_currency", nullable = false, length = 3)
    private Currency quoteCurrency;
    @Column(nullable = false, precision = 20, scale = 10)
    private BigDecimal rate;
    @Column(nullable = false, length = 40)
    private String source;
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
    @Column(name = "effective_to")
    private Instant effectiveTo;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(nullable = false)
    private long version;

    protected CurrencyExchangeRate() {
    }

    /**
     * New rate row with all model invariants enforced at construction so an
     * invalid quote can never reach the database.
     */
    public static CurrencyExchangeRate create(UUID storeId, Currency baseCurrency, Currency quoteCurrency,
            BigDecimal rate, String source, Instant effectiveFrom, Instant effectiveTo, UUID createdByUserId) {
        if (baseCurrency == null || quoteCurrency == null) {
            throw new ValidationException("UNSUPPORTED_CURRENCY", "Base and quote currencies are required");
        }
        if (baseCurrency == quoteCurrency) {
            throw new ValidationException("IDENTICAL_CURRENCY", "Base and quote currency must differ");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new ValidationException("INVALID_RATE", "Exchange rate must be greater than zero");
        }
        if (rate.scale() > 10 || rate.precision() - rate.scale() > 10) {
            throw new ValidationException("INVALID_RATE", "Exchange rate exceeds supported precision numeric(20,10)");
        }
        if (source == null || source.isBlank()) {
            throw new ValidationException("RATE_SOURCE_REQUIRED", "A rate source is required");
        }
        if (effectiveFrom == null) {
            throw new ValidationException("EFFECTIVE_FROM_REQUIRED", "effectiveFrom is required");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new ValidationException("INVALID_EFFECTIVE_PERIOD", "effectiveTo must be after effectiveFrom");
        }
        var r = new CurrencyExchangeRate();
        r.id = UUID.randomUUID();
        r.storeId = storeId;
        r.baseCurrency = baseCurrency;
        r.quoteCurrency = quoteCurrency;
        r.rate = rate;
        r.source = source.trim();
        r.effectiveFrom = effectiveFrom;
        r.effectiveTo = effectiveTo;
        r.active = true;
        r.createdByUserId = createdByUserId;
        r.createdAt = r.updatedAt = Instant.now();
        return r;
    }

    /** Close an open rate (retires it going forward); history is preserved. */
    public void closeAt(Instant when) {
        if (when == null || !when.isAfter(effectiveFrom)) {
            throw new ValidationException("INVALID_EFFECTIVE_PERIOD", "Close time must be after effectiveFrom");
        }
        this.effectiveTo = when;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.active = false;
        this.updatedAt = Instant.now();
    }

    /** True when this row's active window contains {@code at}. */
    public boolean covers(Instant at) {
        return active && !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }

    public boolean isGlobal() {
        return storeId == null;
    }

    /**
     * Directional conversion factor for a requested {@code from -> to} pair, or
     * {@code null} when this rate does not cover the pair. When only the
     * inverse quote exists, the reciprocal is used and flagged as inverted so
     * the caller records the direction honestly.
     */
    public Conversion conversionFor(Currency from, Currency to) {
        if (from == to) {
            return null;
        }
        if (this.baseCurrency == from && this.quoteCurrency == to) {
            return new Conversion(rate, false);
        }
        if (this.baseCurrency == to && this.quoteCurrency == from) {
            return new Conversion(rate, true);
        }
        return null;
    }

    /** A resolved factor: multiplier applied is {@code 1/rate} when inverted. */
    public record Conversion(BigDecimal rate, boolean inverted) {
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public Currency baseCurrency() {
        return baseCurrency;
    }

    public Currency quoteCurrency() {
        return quoteCurrency;
    }

    public BigDecimal rate() {
        return rate;
    }

    public String source() {
        return source;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant effectiveTo() {
        return effectiveTo;
    }

    public boolean active() {
        return active;
    }

    public UUID createdByUserId() {
        return createdByUserId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
