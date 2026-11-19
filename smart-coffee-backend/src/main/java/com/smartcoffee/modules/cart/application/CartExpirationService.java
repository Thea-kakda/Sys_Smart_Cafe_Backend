package com.smartcoffee.modules.cart.application;

import com.smartcoffee.infrastructure.retry.TransientRetryExecutor;
import com.smartcoffee.modules.cart.persistence.CartRepository;
import com.smartcoffee.modules.inventory.application.ReservationMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounded expiry sweep over elapsed carts (Slice 2C §13, hardened in 2E).
 *
 * <p>The scheduler and its cadence are kept; the 2E hardening adds the three
 * operational guarantees the reservation workers also have:
 * <ul>
 *   <li><b>one clock</b> (§16) — the sweep reads {@link Clock#instant()}, the same
 *       time source the domain checks and the expiry tests control, so a cart is
 *       never expired by the worker using a different notion of "now" than the
 *       {@link com.smartcoffee.modules.cart.domain.Cart#expireIfElapsed} guard;</li>
 *   <li><b>bounded retry</b> (§15) — each cart expires in its own transaction and a
 *       recognized transient DB failure (deadlock / serialization) is retried a small
 *       bounded number of times through {@link TransientRetryExecutor}; a business
 *       conflict or a still-broken cart simply moves on to the next one;</li>
 *   <li><b>metrics</b> (§17) — processed / released / failed counters under the
 *       shared {@code reservation.expired.*} names with a {@code sourceType=CART}
 *       tag (never a cart id), keeping cart and order expiry comparable.</li>
 * </ul>
 *
 * <p>Multi-node safety is unchanged and remains deliberate: the candidate query is
 * a bounded {@code expiredIds} scan and each cart is claimed with a per-cart
 * {@code SELECT ... FOR UPDATE} in its own transaction. Two workers that scan the
 * same id therefore still expire it once — the second sees a non-ACTIVE cart and
 * does nothing ({@link CartExpiryProcessor}). Unlike reservations, a cart carries
 * no persistent claim column, so a {@code SKIP LOCKED} batch would need a
 * transaction spanning the whole sweep, which this design deliberately avoids.
 */
@Service
public class CartExpirationService {

    private static final Logger log = LoggerFactory.getLogger(CartExpirationService.class);
    private static final int BATCH_SIZE = 100;

    private final CartRepository carts;
    private final CartExpiryProcessor processor;
    private final TransientRetryExecutor retry;
    private final ReservationMetrics metrics;
    private final Clock clock;

    public CartExpirationService(CartRepository carts, CartExpiryProcessor processor, TransientRetryExecutor retry,
            ReservationMetrics metrics, Clock clock) {
        this.carts = carts;
        this.processor = processor;
        this.retry = retry;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.cart.expiration-poll-ms:60000}")
    public void expire() {
        Instant now = clock.instant();
        List<UUID> due = carts.expiredIds(now, PageRequest.of(0, BATCH_SIZE));
        int expired = 0;
        int failed = 0;
        for (UUID cartId : due) {
            metrics.increment(ReservationMetrics.EXPIRED_PROCESSED, "sourceType", "CART");
            try {
                boolean released = retry.execute("cart_expiry", () -> processor.expire(cartId, now));
                if (released) {
                    expired++;
                    metrics.increment(ReservationMetrics.EXPIRED_RELEASED, "sourceType", "CART");
                }
            } catch (RuntimeException e) {
                // Keep sweeping: a single bad cart must not block the rest. Its own
                // transaction has already rolled back, so the holds stay held and the
                // cart remains ACTIVE and is retried on the next poll.
                failed++;
                metrics.increment(ReservationMetrics.EXPIRED_FAILED, "sourceType", "CART");
                log.warn("cart expiry failed cartId={}", cartId, e);
            }
        }
        if (expired > 0 || failed > 0) {
            log.info("cart expiry sweep finished scanned={} expired={} failed={}", due.size(), expired, failed);
        }
    }

    /** Expire one cart on demand (tests, operational tooling). */
    public boolean expireCart(UUID cartId) {
        return processor.expire(cartId, clock.instant());
    }
}
