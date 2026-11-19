package com.smartcoffee.modules.ordering.domain;

import com.smartcoffee.common.exception.ConflictException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(schema = "ordering", name = "orders")
public class Order {
    @Id
    @Column(name = "order_id")
    private UUID id;
    @Column(name = "order_number", nullable = false, unique = true)
    private String number;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "customer_id")
    private UUID customerId;
    @Column(name = "cart_id", unique = true)
    private UUID cartId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSource source;
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_type", nullable = false)
    private FulfillmentType fulfillmentType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currency;
    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal subtotal;
    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 4)
    private BigDecimal discount;
    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 4)
    private BigDecimal tax;
    @Column(name = "delivery_fee", nullable = false, precision = 14, scale = 4)
    private BigDecimal deliveryFee;
    @Column(name = "rounding_amount", nullable = false, precision = 14, scale = 4)
    private BigDecimal rounding;
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 4)
    private BigDecimal total;
    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;
    /** Promotion identity snapshots (Phase B2, V053); null for no-promotion orders. */
    @Column(name = "promotion_id")
    private UUID promotionId;
    @Column(name = "promotion_code_snapshot", length = 50)
    private String promotionCode;
    @Column(name = "promotion_name_snapshot", length = 150)
    private String promotionName;
    @Column(name = "promotion_type_snapshot", length = 30)
    private String promotionType;
    @Column(name = "promotion_value_snapshot", precision = 14, scale = 4)
    private BigDecimal promotionValue;
    @Column(name = "promotion_eligible_subtotal", precision = 14, scale = 4)
    private BigDecimal promotionEligibleSubtotal;
    @Column(name = "promotion_discount_amount", precision = 14, scale = 4)
    private BigDecimal promotionDiscountAmount;
    @Column(name = "promotion_version")
    private Long promotionVersion;
    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt asc")
    private List<OrderItem> items = new ArrayList<>();
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderStatusHistory> history = new ArrayList<>();

    protected Order() {
    }

    public static Order create(String no, UUID store, UUID customer, UUID cart, FulfillmentType fulfillment,
            String currency, BigDecimal subtotal, BigDecimal tax, BigDecimal total, List<OrderItem> items) {
        return create(no, store, customer, cart, fulfillment, currency, subtotal, tax, total, items, null);
    }

    public static Order create(String no, UUID store, UUID customer, UUID cart, FulfillmentType fulfillment,
            String currency, BigDecimal subtotal, BigDecimal tax, BigDecimal total, List<OrderItem> items,
            OrderPromotion promotion) {
        var o = new Order();
        o.id = UUID.randomUUID();
        o.number = no;
        o.storeId = store;
        o.customerId = customer;
        o.cartId = cart;
        o.source = OrderSource.CUSTOMER_WEB;
        o.fulfillmentType = fulfillment;
        o.status = OrderStatus.AWAITING_PAYMENT;
        o.paymentStatus = PaymentStatus.PENDING;
        o.currency = currency;
        o.subtotal = subtotal;
        o.discount = promotion == null ? BigDecimal.ZERO : promotion.discountAmount();
        o.tax = tax;
        o.deliveryFee = BigDecimal.ZERO;
        o.rounding = BigDecimal.ZERO;
        o.total = total;
        if (promotion != null) {
            o.promotionId = promotion.promotionId();
            o.promotionCode = promotion.code();
            o.promotionName = promotion.name();
            o.promotionType = promotion.type();
            o.promotionValue = promotion.value();
            o.promotionEligibleSubtotal = promotion.eligibleSubtotal();
            o.promotionDiscountAmount = promotion.discountAmount();
            o.promotionVersion = promotion.version();
        }
        o.totalQuantity = items.stream()
                .mapToInt(item -> item.quantity()).sum();
        o.orderedAt = Instant.now();
        o.updatedAt = o.orderedAt;
        items.forEach(i -> {
            i.attach(o);
            o.items.add(i);
        });
        o.addHistory(null, OrderStatus.AWAITING_PAYMENT, null, "Order created");
        return o;
    }

    private void addHistory(OrderStatus from, OrderStatus to, UUID actor, String reason) {
        var h = new OrderStatusHistory(this, from, to, actor, reason);
        history.add(h);
    }

    public UUID id() {
        return id;
    }

    public String number() {
        return number;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID customerId() {
        return customerId;
    }

    public UUID cartId() {
        return cartId;
    }

    public OrderStatus status() {
        return status;
    }

    public OrderSource source() {
        return source;
    }

    public FulfillmentType fulfillmentType() {
        return fulfillmentType;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public PaymentStatus paymentStatus() {
        return paymentStatus;
    }

    public String currency() {
        return currency;
    }

    public BigDecimal subtotal() {
        return subtotal;
    }

    public BigDecimal discount() {
        return discount;
    }

    public BigDecimal tax() {
        return tax;
    }

    public BigDecimal deliveryFee() {
        return deliveryFee;
    }

    public BigDecimal rounding() {
        return rounding;
    }

    public BigDecimal total() {
        return total;
    }

    public int totalQuantity() {
        return totalQuantity;
    }

    public Instant orderedAt() {
        return orderedAt;
    }

    public long version() {
        return version;
    }

    public UUID promotionId() {
        return promotionId;
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

    public BigDecimal promotionEligibleSubtotal() {
        return promotionEligibleSubtotal;
    }

    public BigDecimal promotionDiscountAmount() {
        return promotionDiscountAmount;
    }

    public Long promotionVersion() {
        return promotionVersion;
    }

    /** Snapshot for receipts/history; null when the order used no promotion. */
    public OrderPromotion promotion() {
        if (promotionId == null)
            return null;
        return new OrderPromotion(promotionId, promotionCode, promotionName, promotionType, promotionValue,
                promotionEligibleSubtotal, promotionDiscountAmount, promotionVersion == null ? 0L : promotionVersion);
    }

    public List<OrderItem> items() {
        return List.copyOf(items);
    }

    public void cancel(UUID actor, String reason) {
        if (status == OrderStatus.COMPLETED || status == OrderStatus.CANCELLED)
            throw new ConflictException("ORDER_STATE_CONFLICT", "Order cannot be cancelled");
        var old = status;
        status = OrderStatus.CANCELLED;
        updatedAt = Instant.now();
        addHistory(old, status, actor, reason);
    }

    /**
     * Settlement (Slice 2D): the one place an order becomes CONFIRMED/PAID. The
     * inventory reservation consumption happens in the same transaction, in
     * {@code JpaPaymentOrderAdapter.confirm}.
     */
    public void paymentCompleted(UUID actor) {
        if (status == OrderStatus.CONFIRMED
                && paymentStatus == PaymentStatus.PAID) {
            return;
        }

        if (status != OrderStatus.AWAITING_PAYMENT
                || paymentStatus != PaymentStatus.PENDING) {
            throw new ConflictException(
                    "ORDER_STATE_CONFLICT",
                    "Payment cannot be completed for the current order state");
        }

        OrderStatus previousStatus = status;

        paymentStatus = PaymentStatus.PAID;
        status = OrderStatus.CONFIRMED;
        updatedAt = Instant.now();

        addHistory(
                previousStatus,
                status,
                actor,
                "Payment completed");
    }

    public void paymentExpired(UUID actor) {
        if (status == OrderStatus.CANCELLED
                && paymentStatus == PaymentStatus.FAILED) {
            return;
        }

        if (status != OrderStatus.AWAITING_PAYMENT
                || paymentStatus != PaymentStatus.PENDING) {
            throw new ConflictException(
                    "ORDER_STATE_CONFLICT",
                    "Payment cannot expire for the current order state");
        }

        OrderStatus previousStatus = status;

        paymentStatus = PaymentStatus.FAILED;
        status = OrderStatus.CANCELLED;
        updatedAt = Instant.now();

        addHistory(
                previousStatus,
                status,
                actor,
                "Payment window expired");
    }

    public void productionPreparing(UUID actor) {
        if (status == OrderStatus.PREPARING) {
            return;
        }

        if (status != OrderStatus.CONFIRMED) {
            throw new ConflictException(
                    "ORDER_STATE_CONFLICT",
                    "Production can start only for a confirmed order");
        }

        if (paymentStatus != PaymentStatus.PAID) {
            throw new ConflictException(
                    "PAYMENT_STATE_CONFLICT",
                    "Production can start only for a paid order");
        }

        OrderStatus previousStatus = status;

        status = OrderStatus.PREPARING;
        updatedAt = Instant.now();

        addHistory(
                previousStatus,
                status,
                actor,
                "Production started");
    }

    public void productionReady(UUID actor) {
        if (status == OrderStatus.READY) {
            return;
        }

        if (status != OrderStatus.PREPARING) {
            throw new ConflictException(
                    "ORDER_STATE_CONFLICT",
                    "Order can become ready only while production is in progress");
        }

        OrderStatus previousStatus = status;

        status = OrderStatus.READY;
        updatedAt = Instant.now();

        addHistory(
                previousStatus,
                status,
                actor,
                "Production completed");
    }

    /**
     * Administrative completion command (Full-stack wiring §1.4): a READY order
     * is handed over and closed. Idempotent by early return, mirroring
     * paymentCompleted; any other source state is a genuine 409.
     */
    public void completed(UUID actor) {
        if (status == OrderStatus.COMPLETED) {
            return;
        }

        if (status != OrderStatus.READY) {
            throw new ConflictException(
                    "ORDER_STATE_CONFLICT",
                    "Order can be completed only when ready for handover");
        }

        OrderStatus previousStatus = status;

        status = OrderStatus.COMPLETED;
        updatedAt = Instant.now();

        addHistory(
                previousStatus,
                status,
                actor,
                "Order completed");
    }
}

