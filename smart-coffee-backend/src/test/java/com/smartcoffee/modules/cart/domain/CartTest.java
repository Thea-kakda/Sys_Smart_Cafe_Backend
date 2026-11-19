package com.smartcoffee.modules.cart.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the POS cart duplicate-key warning
 * ("Encountered two children with the same key ..." in CartPanel.tsx).
 *
 * The React key is the cart line's stable id (CartItem.id / cartItemId), which is
 * already correct on the client. The duplicate came from the server: the cart
 * entity graph join-fetches Cart.items (a List bag) together with each item's
 * customizations, so Hibernate returns one row per (item x customization) and —
 * because the outer collection is a List, not a Set — the SAME cart line is
 * repeated N times. CartMapper then emits N responses sharing one cartItemId, and
 * Cart.subtotal() double-counts. Cart.items() must normalise those duplicate
 * references to a single entry per line WITHOUT collapsing genuinely distinct
 * lines (same product, different configuration).
 */
class CartTest {

    private static Cart newCart() {
        return Cart.customer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "USD",
                Instant.now().plus(24, ChronoUnit.HOURS));
    }

    private static CartItem line(UUID productId, int quantity, String basePrice, List<CartItemCustomization> customs) {
        return CartItem.create(productId, "SKU-" + productId, "Product", quantity, new BigDecimal(basePrice), null,
                customs);
    }

    private static CartItemCustomization custom(String name, String price) {
        return new CartItemCustomization(UUID.randomUUID(), UUID.randomUUID(), "Group", name, new BigDecimal(price), 1);
    }

    /** Simulate the Hibernate join artefact: repeat each managed line reference. */
    @SuppressWarnings("unchecked")
    private static void simulateJoinDuplication(Cart cart, int copies) throws Exception {
        Field f = Cart.class.getDeclaredField("items");
        f.setAccessible(true);
        List<CartItem> raw = (List<CartItem>) f.get(cart);
        List<CartItem> snapshot = new ArrayList<>(raw);
        for (int c = 1; c < copies; c++) {
            raw.addAll(snapshot);
        }
    }

    @Test
    void aLineWithMultipleCustomizationsIsExposedOnceNotRepeated() throws Exception {
        Cart cart = newCart();
        UUID product = UUID.fromString("30000000-0000-0000-0000-000000000001");
        CartItem single = line(product, 2, "3.50", List.of(custom("Oat Milk", "0.30"), custom("Extra Shot", "0.20")));
        cart.add(single);

        // Two customizations -> Hibernate would yield the line twice.
        simulateJoinDuplication(cart, 2);
        assertEquals(2, directFieldSize(cart), "sanity: the raw bag really holds the duplicate");

        List<CartItem> viewed = cart.items();
        assertEquals(1, viewed.size(), "one physical cart line must render exactly once");
        assertEquals(single.id(), viewed.get(0).id());

        // subtotal must count the line once, not twice.
        assertEquals(0, cart.subtotal().compareTo(single.lineTotal()),
                "subtotal must not double-count the duplicated line");
    }

    @Test
    void distinctLinesForTheSameProductRemainSeparate() throws Exception {
        Cart cart = newCart();
        UUID product = UUID.fromString("30000000-0000-0000-0000-000000000001");

        CartItem lineA = line(product, 1, "3.50", List.of(custom("Iced", "0.00"), custom("50% Sugar", "0.00")));
        CartItem lineB = line(product, 1, "3.50", List.of(custom("Hot", "0.00"), custom("100% Sugar", "0.50")));
        cart.add(lineA);
        cart.add(lineB);

        // Both lines carry two customizations -> each duplicated by the join.
        simulateJoinDuplication(cart, 2);

        List<CartItem> viewed = cart.items();
        // Two legitimate, differently-configured lines must stay separate (not merged,
        // not quadruplicated).
        assertEquals(2, viewed.size(), "the two distinct lines must both survive, once each");

        Set<UUID> ids = new HashSet<>();
        for (CartItem ci : viewed) {
            assertTrue(ids.add(ci.id()), "no duplicate cart-line id may reach the renderer");
        }
        assertTrue(ids.contains(lineA.id()) && ids.contains(lineB.id()));

        // Same product id is allowed across the two lines.
        assertEquals(lineA.productId(), lineB.productId());

        BigDecimal expected = lineA.lineTotal().add(lineB.lineTotal());
        assertEquals(0, cart.subtotal().compareTo(expected), "subtotal must equal the sum of distinct lines");
    }

    @Test
    void orderIsPreservedByCreationSequence() {
        Cart cart = newCart();
        UUID product = UUID.randomUUID();
        CartItem first = line(product, 1, "1.00", List.of());
        CartItem second = line(product, 1, "2.00", List.of());
        cart.add(first);
        cart.add(second);

        List<CartItem> viewed = cart.items();
        assertEquals(first.id(), viewed.get(0).id());
        assertEquals(second.id(), viewed.get(1).id());
    }

    @SuppressWarnings("unchecked")
    private static int directFieldSize(Cart cart) {
        try {
            Field f = Cart.class.getDeclaredField("items");
            f.setAccessible(true);
            return ((List<CartItem>) f.get(cart)).size();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
