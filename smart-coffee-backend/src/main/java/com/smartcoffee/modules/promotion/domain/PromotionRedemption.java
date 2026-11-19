package com.smartcoffee.modules.promotion.domain;

import com.smartcoffee.common.exception.ConflictException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One Order's claim on one Promotion. The lifecycle is RESERVED at checkout,
 * then either REDEEMED at authoritative payment settlement or terminal
 * RELEASED/EXPIRED on an accepted cancellation/expiry. The definition snapshot
 * (code, name, type, value) and the eligible subtotal keep the grant
 * reconstructible after the campaign changes (Phase B2).
 */
@Entity
@Table(schema = "promotion", name = "promotion_redemptions", uniqueConstraints = @UniqueConstraint(columnNames = {
        "promotion_id", "order_id" }))
public class PromotionRedemption {
    @Id
    @Column(name = "redemption_id")
    private UUID id;
    @Column(name = "promotion_id", nullable = false)
    private UUID promotionId;
    @Column(name = "customer_id")
    private UUID customerId;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RedemptionStatus status;
    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 4)
    private BigDecimal discountAmount;
    @Column(name = "eligible_subtotal", precision = 14, scale = 4)
    private BigDecimal eligibleSubtotal;
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currency;
    @Column(name = "promotion_code_snapshot", length = 50)
    private String promotionCode;
    @Column(name = "promotion_name_snapshot", length = 150)
    private String promotionName;
    @Column(name = "promotion_type_snapshot", length = 30)
    private String promotionType;
    @Column(name = "promotion_value_snapshot", precision = 14, scale = 4)
    private BigDecimal promotionValue;
    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "redeemed_at")
    private Instant redeemedAt;
    @Column(name = "released_at")
    private Instant releasedAt;
    @Column(name = "release_reason", length = 60)
    private String releaseReason;
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;
    @Column(name = "correlation_id", length = 100)
    private String correlationId;
    @Version
    private long version;

    protected PromotionRedemption() {
    }

    public static PromotionRedemption reserve(UUID promotion, UUID customer, UUID order, UUID store,
            BigDecimal discount, String currency, Instant expires) {
        return reserve(promotion, customer, order, store, discount, null, currency, expires, null, null, null, null,
                null, null);
    }

    public static PromotionRedemption reserve(UUID promotion, UUID customer, UUID order, UUID store,
            BigDecimal discount, BigDecimal eligibleSubtotal, String currency, Instant expires, String code,
            String name, String type, BigDecimal value, String idempotencyKey, String correlationId) {
        var r = new PromotionRedemption();
        r.id = UUID.randomUUID();
        r.promotionId = promotion;
        r.customerId = customer;
        r.orderId = order;
        r.storeId = store;
        r.discountAmount = discount;
        r.eligibleSubtotal = eligibleSubtotal;
        r.currency = currency;
        r.promotionCode = code;
        r.promotionName = name;
        r.promotionType = type;
        r.promotionValue = value;
        r.status = RedemptionStatus.RESERVED;
        r.reservedAt = Instant.now();
        r.expiresAt = expires;
        r.idempotencyKey = idempotencyKey;
        r.correlationId = correlationId;
        return r;
    }

    private void active() {
        if (status != RedemptionStatus.RESERVED)
            throw new ConflictException("PROMOTION_REDEMPTION_CONFLICT", "Redemption is not reserved");
    }

    public void redeem() {
        active();
        status = RedemptionStatus.REDEEMED;
        redeemedAt = Instant.now();
    }

    public void release() {
        release(null);
    }

    public void release(String reason) {
        active();
        status = RedemptionStatus.RELEASED;
        releasedAt = Instant.now();
        releaseReason = reason;
    }

    public void expire(String reason) {
        active();
        status = RedemptionStatus.EXPIRED;
        releasedAt = Instant.now();
        releaseReason = reason;
    }

    public UUID id() {
        return id;
    }

    public UUID promotionId() {
        return promotionId;
    }

    public UUID customerId() {
        return customerId;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID storeId() {
        return storeId;
    }

    public RedemptionStatus status() {
        return status;
    }

    public BigDecimal discountAmount() {
        return discountAmount;
    }

    public BigDecimal eligibleSubtotal() {
        return eligibleSubtotal;
    }

    public String currency() {
        return currency;
    }

    public String promotionCode() {
        return promotionCode;
    }

    public String promotionName() {
        return promotionName;
    }

    public String promotionType() {
        return promotionType;
    }

    public BigDecimal promotionValue() {
        return promotionValue;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant redeemedAt() {
        return redeemedAt;
    }

    public Instant releasedAt() {
        return releasedAt;
    }

    public String releaseReason() {
        return releaseReason;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String correlationId() {
        return correlationId;
    }
}
