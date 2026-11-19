package com.smartcoffee.modules.payment.application;

import com.smartcoffee.common.exception.*;
import com.smartcoffee.modules.cart.application.CurrentCustomerResolver;
import com.smartcoffee.modules.inventory.application.InventoryReservationFinalizationService;
import com.smartcoffee.modules.ordering.domain.OrderStatus;
import com.smartcoffee.modules.ordering.domain.PaymentStatus;
import com.smartcoffee.modules.ordering.persistence.OrderRepository;
import com.smartcoffee.modules.promotion.application.PromotionRedemptionLifecycleService;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaPaymentOrderAdapter implements PaymentOrderPort {
    private final OrderRepository orders;
    private final CurrentCustomerResolver customers;
    private final InventoryReservationFinalizationService reservations;
    private final PromotionRedemptionLifecycleService promotions;

    public JpaPaymentOrderAdapter(OrderRepository o, CurrentCustomerResolver c,
            InventoryReservationFinalizationService r, PromotionRedemptionLifecycleService p) {
        orders = o;
        customers = c;
        reservations = r;
        promotions = p;
    }
    

    @Transactional(readOnly = true)
    public OrderPaymentView loadOwned(UUID id, UUID user) {
        UUID customer = customers.customerId(user);
        var o = orders.findByIdAndCustomerId(id, customer)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return view(o);
    }

    /**
     * Non-ownership administrative read (§1.3). Authorization is the caller's
     * job (order:admin-read + store scoping in ReceiptQueryService); this only
     * resolves the aggregate, 404 when the order does not exist.
     */
    @Override
    @Transactional(readOnly = true)
    public OrderPaymentView load(UUID id) {
        var o = orders.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found"));
        return view(o);
    }

    /** Reads the persisted order snapshot only — never the live promotion row. */
    private static OrderPaymentView view(com.smartcoffee.modules.ordering.domain.Order o) {
        var p = o.promotion();
        return new OrderPaymentView(o.id(), o.storeId(), o.customerId(), o.currency(), o.total(), o.tax(),
                o.status().name(), o.number(), o.subtotal(), o.discount(), p == null ? null : p.promotionId(),
                p == null ? null : p.code(), p == null ? null : p.name(), p == null ? null : p.type(),
                p == null ? null : p.value(), p == null ? null : p.eligibleSubtotal(),
                p == null ? null : p.discountAmount(), p == null ? 0L : p.version());
    }

    /**
     * Payment settlement (Slice 2D). This is the one business event at which stock
     * leaves inventory: cash is settled synchronously and KHQR is confirmed after
     * the provider accepts, and both reach this method inside a single database
     * transaction, so order confirmation and consumption commit together.
     *
     * <p>Consumption draws down the reservation snapshot the order already owned
     * (converted from the cart at checkout) — it never re-reserves and never
     * recalculates demand. A replay for an already-confirmed order is a 409, while
     * an already-consumed reservation is a silent no-op beneath it, so the same
     * quantity can never be drawn twice.
     */
    @Transactional
    public void confirm(UUID id, UUID actor) {
        var o = orders.lock(id).orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found"));
        if (o.status() == OrderStatus.CONFIRMED && o.paymentStatus() == PaymentStatus.PAID) {
            throw new ConflictException("ORDER_ALREADY_PAID", "Order already paid");
        }
        o.paymentCompleted(actor);
        reservations.consumeOrder(id, actor);
        // Authoritative settlement boundary (Phase B2): the RESERVED promotion
        // becomes REDEEMED in this same transaction, exactly once on replay.
        promotions.redeemForOrder(id, actor);
    }

    @Transactional
    public void expire(UUID id) {
        orders.lock(id).ifPresent(o -> o.paymentExpired(null));
    }
}

