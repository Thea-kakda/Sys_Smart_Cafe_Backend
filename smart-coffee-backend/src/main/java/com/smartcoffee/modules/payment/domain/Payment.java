package com.smartcoffee.modules.payment.domain;

import com.smartcoffee.common.exception.ConflictException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "payment", name = "payments")
public class Payment {
    @Id
    @Column(name = "payment_id")
    private UUID id;
    @Column(name = "payment_reference", nullable = false, unique = true)
    private String reference;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "payment_method_id", nullable = false)
    private UUID methodId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currency;
    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal amount;
    @Column(name = "amount_received", precision = 14, scale = 4)
    private BigDecimal amountReceived;
    @Column(name = "change_amount", precision = 14, scale = 4)
    private BigDecimal changeAmount;
    @Column(name = "provider_name")
    private String providerName;
    @Column(name = "provider_reference")
    private String providerReference;
    @Column(name = "failure_code")
    private String failureCode;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "paid_at")
    private Instant paidAt;
    @Column(name = "failed_at")
    private Instant failedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    // ---- Currency-conversion snapshot (Checkpoint 5D). Denormalized, permanent;
    // ---- never re-derived from the live rate table. Null for non-cash payments.
    @Column(name = "tender_currency", length = 3)
    private String tenderCurrency;
    @Column(name = "tender_amount_received", precision = 14, scale = 4)
    private BigDecimal tenderAmountReceived;
    @Column(name = "exchange_rate_id")
    private UUID exchangeRateId;
    @Column(name = "applied_exchange_rate", precision = 20, scale = 10)
    private BigDecimal appliedExchangeRate;
    @Column(name = "rate_base_currency", length = 3)
    private String rateBaseCurrency;
    @Column(name = "rate_quote_currency", length = 3)
    private String rateQuoteCurrency;
    @Column(name = "exchange_rate_inverted")
    private Boolean exchangeRateInverted;
    @Column(name = "settlement_amount_received", precision = 14, scale = 4)
    private BigDecimal settlementAmountReceived;
    @Column(name = "settlement_change_amount", precision = 14, scale = 4)
    private BigDecimal settlementChangeAmount;
    @Column(name = "tender_change_amount", precision = 14, scale = 4)
    private BigDecimal tenderChangeAmount;
    @Column(name = "change_currency", length = 3)
    private String changeCurrency;
    @Column(name = "rounding_amount", precision = 14, scale = 4)
    private BigDecimal roundingAmount;
    @Version
    private long version;

    protected Payment() {
    }

    public static Payment pending(UUID order, UUID store, UUID method, String currency, BigDecimal amount,
            Instant expiry) {
        var p = new Payment();
        p.id = UUID.randomUUID();
        p.reference = "PAY-" + p.id;
        p.orderId = order;
        p.storeId = store;
        p.methodId = method;
        p.currency = currency;
        p.amount = amount;
        p.status = PaymentStatus.PENDING;
        p.expiresAt = expiry;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        return p;
    }

    private void requireOpen() {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.PROCESSING)
            throw new ConflictException("PAYMENT_STATE_CONFLICT", "Payment is not open");
    }

    public void processing(String provider, String providerRef) {
        requireOpen();
        status = PaymentStatus.PROCESSING;
        providerName = provider;
        providerReference = providerRef;
        updatedAt = Instant.now();
    }

    public void paid(BigDecimal received, BigDecimal change) {
        requireOpen();
        status = PaymentStatus.PAID;
        amountReceived = received;
        changeAmount = change;
        paidAt = updatedAt = Instant.now();
    }

    public void failed(String code) {
        requireOpen();
        status = PaymentStatus.FAILED;
        failureCode = code;
        failedAt = updatedAt = Instant.now();
    }

    public void expired() {
        requireOpen();
        status = PaymentStatus.EXPIRED;
        updatedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID methodId() {
        return methodId;
    }

    public String reference() {
        return reference;
    }

    public PaymentStatus status() {
        return status;
    }

    public String currency() {
        return currency;
    }

    public BigDecimal amount() {
        return amount;
    }

    public BigDecimal amountReceived() {
        return amountReceived;
    }

    public BigDecimal changeAmount() {
        return changeAmount;
    }

    public String providerReference() {
        return providerReference;
    }

    public String failureCode() {
        return failureCode;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant paidAt() {
        return paidAt;
    }

    /**
     * Records the immutable currency-conversion snapshot for this payment
     * (Checkpoint 5D). Called once, at settlement, before the row is committed.
     * The values are copied defensively so a later rate change cannot leak in.
     */
    public void applyConversion(PaymentConversion c) {
        if (c == null) {
            return;
        }
        this.tenderCurrency = c.tenderCurrency();
        this.tenderAmountReceived = c.tenderAmountReceived();
        this.exchangeRateId = c.exchangeRateId();
        this.appliedExchangeRate = c.appliedExchangeRate();
        this.rateBaseCurrency = c.rateBaseCurrency();
        this.rateQuoteCurrency = c.rateQuoteCurrency();
        this.exchangeRateInverted = c.exchangeRateInverted();
        this.settlementAmountReceived = c.settlementAmountReceived();
        this.settlementChangeAmount = c.settlementChangeAmount();
        this.tenderChangeAmount = c.tenderChangeAmount();
        this.changeCurrency = c.changeCurrency();
        this.roundingAmount = c.roundingAmount();
    }

    /** Reconstructs the persisted conversion snapshot, or null if none applies. */
    public PaymentConversion conversionSnapshot() {
        if (tenderCurrency == null) {
            return null;
        }
        return new PaymentConversion(tenderCurrency, tenderAmountReceived, exchangeRateId, appliedExchangeRate,
                rateBaseCurrency, rateQuoteCurrency, Boolean.TRUE.equals(exchangeRateInverted),
                settlementAmountReceived, settlementChangeAmount, tenderChangeAmount, changeCurrency, roundingAmount);
    }
}

