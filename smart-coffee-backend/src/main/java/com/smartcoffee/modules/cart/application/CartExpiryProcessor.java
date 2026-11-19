package com.smartcoffee.modules.cart.application;

import com.smartcoffee.modules.cart.domain.Cart;
import com.smartcoffee.modules.cart.persistence.CartRepository;
import com.smartcoffee.modules.inventory.application.CartReservationService;
import com.smartcoffee.modules.inventory.application.ReservationReasons;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires one cart and gives back its holds, in a transaction of its own
 * (Slice 2C §13).
 *
 * <p>Two workers sweeping the same cart cannot double-release: the cart row is
 * claimed with {@code SELECT ... FOR UPDATE} and {@link Cart#expireIfElapsed}
 * only reports a transition for an ACTIVE, elapsed cart, so the second worker
 * sees a non-ACTIVE cart and does nothing. Release itself is idempotent as well
 * ({@link CartReservationService} skips non-ACTIVE reservations).
 *
 * <p>The separate bean is deliberate: a per-cart transaction boundary is what
 * lets {@link CartExpirationService} continue with the rest of the batch after
 * one cart fails. A self-invoked {@code @Transactional} method on the scheduler
 * bean would silently run in the caller's (absent) transaction.
 */
@Service
public class CartExpiryProcessor {

    private final CartRepository carts;
    private final CartReservationService reservations;

    public CartExpiryProcessor(CartRepository carts, CartReservationService reservations) {
        this.carts = carts;
        this.reservations = reservations;
    }

    /**
     * @return true when this call expired the cart and released its holds
     */
    @Transactional
    public boolean expire(UUID cartId, Instant now) {
        Cart cart = carts.lockById(cartId).orElse(null);
        if (cart == null || !cart.expireIfElapsed(now)) {
            return false;
        }
        // System actor: automated expiry has no end user.
        reservations.releaseCart(cart.id(), ReservationReasons.CART_EXPIRED, null);
        return true;
    }
}
