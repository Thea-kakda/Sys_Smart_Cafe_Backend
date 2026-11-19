package com.smartcoffee.modules.payment.application;

import com.smartcoffee.modules.inventory.application.InventoryReservationFinalizationService;
import com.smartcoffee.modules.inventory.application.ReservationReasons;
import com.smartcoffee.modules.payment.persistence.PaymentRepository;
import com.smartcoffee.modules.promotion.application.PromotionRedemptionLifecycleService;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes the payment window of abandoned attempts (Slice 2D).
 *
 * <p>The order is cancelled and the inventory hold it owns is released with the
 * {@code PAYMENT_FAILED} reason, exactly once: the release is a no-op when the
 * reservation was already consumed (the payment had in fact settled) or already
 * released (cancelled administratively). Releasing here never touches
 * {@code current_stock}, only the held quantity.
 */
@Service
public class PaymentExpirationService {
    private final PaymentRepository payments;
    private final PaymentOrderPort orders;
    private final InventoryReservationFinalizationService reservations;
    private final PromotionRedemptionLifecycleService promotions;

    public PaymentExpirationService(PaymentRepository p, PaymentOrderPort o,
            InventoryReservationFinalizationService r, PromotionRedemptionLifecycleService pr) {
        payments = p;
        orders = o;
        reservations = r;
        promotions = pr;
    }

    @Scheduled(fixedDelayString = "${app.payment.expiration-poll-ms:60000}")
    @Transactional
    public void run() {
        for (var id : payments.expired(Instant.now(), PageRequest.of(0, 100))) {
            var p = payments.lock(id).orElseThrow();
            if (p.expiresAt() != null && !p.expiresAt().isAfter(Instant.now())) {
                p.expired();
                orders.expire(p.orderId());
                reservations.releaseOrder(p.orderId(), ReservationReasons.PAYMENT_FAILED, null);
                // The order is cancelled by its own authoritative expiry, so the
                // still-RESERVED promotion is given back in the same transaction.
                promotions.expireForOrder(p.orderId(), "PAYMENT_EXPIRED", null);
            }
        }
    }
}
