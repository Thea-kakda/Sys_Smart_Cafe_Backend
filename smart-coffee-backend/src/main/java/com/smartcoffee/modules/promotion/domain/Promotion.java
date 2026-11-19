package com.smartcoffee.modules.promotion.domain;

import jakarta.persistence.*;
import java.math.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "promotion", name = "promotions")
public class Promotion {
    @Id
    @Column(name = "promotion_id")
    private UUID id;
    @Column(name = "promotion_code", unique = true)
    private String code;
    @Column(name = "promotion_name", nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;
    @Column(name = "discount_value", nullable = false, precision = 14, scale = 4)
    private BigDecimal value;
    @Column(name = "minimum_order_amount", nullable = false, precision = 14, scale = 4)
    private BigDecimal minimumOrderAmount;
    @Column(name = "maximum_discount_amount", precision = 14, scale = 4)
    private BigDecimal maximumDiscountAmount;
    @Column(name = "currency_code", length = 3)
    private String currency;
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PromotionStatus status;
    @Column(name = "usage_limit")
    private Integer usageLimit;
    @Column(name = "per_customer_limit")
    private Integer perCustomerLimit;
    @Column(name = "usage_count", nullable = false)
    private int usageCount;
    @Column(nullable = false)
    private boolean stackable;
    @Column(nullable = false)
    private boolean automatic;
    @Column(nullable = false)
    private int priority;
    @Version
    private long version;

    protected Promotion() {
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public boolean eligibleAt(Instant now) {
        return status == PromotionStatus.ACTIVE && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    public BigDecimal discount(BigDecimal subtotal) {
        BigDecimal d = discountType == DiscountType.PERCENTAGE ? subtotal.multiply(value.movePointLeft(2)) : value;
        if (maximumDiscountAmount != null && d.compareTo(maximumDiscountAmount) > 0)
            d = maximumDiscountAmount;
        return d.min(subtotal).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal minimumOrderAmount() {
        return minimumOrderAmount;
    }

    public String currency() {
        return currency;
    }

    public Integer usageLimit() {
        return usageLimit;
    }

    public Integer perCustomerLimit() {
        return perCustomerLimit;
    }

    public int usageCount() {
        return usageCount;
    }

    public boolean automatic() {
        return automatic;
    }

    public int priority() {
        return priority;
    }

    public DiscountType discountType() {
        return discountType;
    }

    public BigDecimal discountValue() {
        return value;
    }

    public BigDecimal maximumDiscountAmount() {
        return maximumDiscountAmount;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public PromotionStatus status() {
        return status;
    }

    public boolean stackable() {
        return stackable;
    }

    public long version() {
        return version;
    }

    /**
     * Atomic capacity claim under the checkout transaction's promotion row lock
     * (Phase B2). The guarded check is the final word even when advisory checks
     * ran earlier: it can never push usage_count past usage_limit.
     */
    public void reserveUsage() {
        if (usageLimit != null && usageCount >= usageLimit)
            throw new com.smartcoffee.common.exception.ConflictException("PROMOTION_USAGE_LIMIT_REACHED",
                    "Promotion usage limit reached");
        usageCount++;
    }

    /**
     * Production aggregate factory for administration (Master Prompt Phase 5).
     * Codes are normalized uppercase at the service layer before creation. Usage
     * count starts at zero; optimistic locking is handled by @Version.
     */
    public static Promotion create(String code, String name, DiscountType discountType, BigDecimal value,
            BigDecimal minimumOrderAmount, BigDecimal maximumDiscountAmount, String currency, Instant startsAt,
            Instant endsAt, PromotionStatus status, Integer usageLimit, Integer perCustomerLimit, boolean stackable,
            boolean automatic, int priority) {
        Promotion p = new Promotion();
        p.id = UUID.randomUUID();
        p.code = code;
        p.name = name;
        p.discountType = discountType;
        p.value = value;
        p.minimumOrderAmount = minimumOrderAmount;
        p.maximumDiscountAmount = maximumDiscountAmount;
        p.currency = currency;
        p.startsAt = startsAt;
        p.endsAt = endsAt;
        p.status = status;
        p.usageLimit = usageLimit;
        p.perCustomerLimit = perCustomerLimit;
        p.usageCount = 0;
        p.stackable = stackable;
        p.automatic = automatic;
        p.priority = priority;
        return p;
    }

    /**
     * Rewrites the campaign definition only. usage_count, status and code are
     * never touched here: redemptions stay authoritative (status changes flow
     * through changeStatus, usage through claim/releaseClaim).
     */
    public void updateDefinition(DiscountType discountType, BigDecimal value, BigDecimal minimumOrderAmount,
            BigDecimal maximumDiscountAmount, String currency, Instant startsAt, Instant endsAt, Integer usageLimit,
            Integer perCustomerLimit, boolean stackable, boolean automatic, int priority) {
        this.discountType = discountType;
        this.value = value;
        this.minimumOrderAmount = minimumOrderAmount;
        this.maximumDiscountAmount = maximumDiscountAmount;
        this.currency = currency;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.usageLimit = usageLimit;
        this.perCustomerLimit = perCustomerLimit;
        this.stackable = stackable;
        this.automatic = automatic;
        this.priority = priority;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeStatus(PromotionStatus newStatus) {
        this.status = newStatus;
    }
}

