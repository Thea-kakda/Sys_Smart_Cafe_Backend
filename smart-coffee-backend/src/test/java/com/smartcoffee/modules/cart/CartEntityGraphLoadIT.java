package com.smartcoffee.modules.cart;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.modules.cart.domain.CartStatus;
import com.smartcoffee.modules.cart.persistence.CartRepository;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regression guard for the Checkpoint 11 blocker: the cart entity graph
 * join-fetches both {@code Cart.items} and {@code CartItem.customizations}.
 * With both mapped as List "bags", Hibernate rejected the query at build time
 * with MultipleBagFetchException — even for an empty result set — so every cart
 * read crashed with HTTP 500. Converting the customizations collection to a Set
 * (leaving a single bag) fixes it. These queries must now compile and run
 * cleanly for arbitrary ids, proving the fetch graph itself is valid.
 */
class CartEntityGraphLoadIT extends IntegrationTestBase {

    @Autowired
    CartRepository carts;

    @Test
    void findFirstCurrentCartGraphDoesNotThrowMultipleBag() {
        var result = assertDoesNotThrow(() -> carts
                .findFirstByCustomerIdAndStoreIdAndStatusOrderByCreatedAtDesc(
                        UUID.randomUUID(), UUID.randomUUID(), CartStatus.ACTIVE));
        assertTrue(result.isEmpty());
    }

    @Test
    void findByIdAndCustomerIdGraphDoesNotThrowMultipleBag() {
        var result = assertDoesNotThrow(() -> carts.findByIdAndCustomerId(UUID.randomUUID(), UUID.randomUUID()));
        assertTrue(result.isEmpty());
    }

    @Test
    @Transactional
    void lockOwnedGraphDoesNotThrowMultipleBag() {
        var result = assertDoesNotThrow(() -> carts.lockOwned(UUID.randomUUID(), UUID.randomUUID()));
        assertTrue(result.isEmpty());
    }
}
