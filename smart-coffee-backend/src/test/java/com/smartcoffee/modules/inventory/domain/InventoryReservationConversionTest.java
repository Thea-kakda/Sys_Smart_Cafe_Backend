package com.smartcoffee.modules.inventory.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Domain-level guards for the Slice 2D ownership transition: only a live
 * cart-owned hold may be converted, the conversion is metadata-only (lines and
 * quantities untouched), and a terminal reservation can never be converted,
 * consumed or released a second time.
 */
class InventoryReservationConversionTest {

    private final UUID store = UUID.randomUUID();
    private final UUID cart = UUID.randomUUID();
    private final UUID cartItem = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID orderLine = UUID.randomUUID();
    private final UUID beans = UUID.randomUUID();
    private final UUID milk = UUID.randomUUID();

    @Test
    void conversionKeepsLinesAndQuantitiesButMovesOwnership() {
        InventoryReservation hold = cartHold();

        hold.converted(order, orderLine, UUID.randomUUID(), Instant.now().plusSeconds(900));

        assertEquals(ReservationStatus.CONVERTED, hold.status());
        assertEquals("CART_ITEM", hold.sourceType());
        assertEquals(cartItem, hold.cartItemId(), "the original cart line stays as provenance");
        assertEquals(order, hold.convertedOrderId());
        assertEquals(orderLine, hold.convertedOrderLineId());
        assertNotNull(hold.convertedAt());
        assertEquals(2, hold.lines().size());
        assertEquals(0, new BigDecimal("18").compareTo(hold.demandSnapshot().get(beans)));
        assertEquals(0, new BigDecimal("180").compareTo(hold.demandSnapshot().get(milk)));
        assertNull(hold.releaseReason(), "a conversion is not a release");
        assertNull(hold.consumedAt());
    }

    @Test
    void aReleasedHoldCannotBeConverted() {
        InventoryReservation hold = cartHold();
        hold.released("CART_ITEM_REMOVED");

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> hold.converted(order, orderLine, UUID.randomUUID(), null));

        assertEquals("RESERVATION_STATE_CONFLICT", conflict.code());
        assertEquals(ReservationStatus.RELEASED, hold.status());
        assertNull(hold.convertedOrderId());
    }

    @Test
    void aConsumedHoldCannotBeConverted() {
        InventoryReservation hold = cartHold();
        hold.consumed(UUID.randomUUID());

        assertThrows(ConflictException.class,
                () -> hold.converted(order, orderLine, UUID.randomUUID(), null));
        assertEquals(ReservationStatus.CONSUMED, hold.status());
    }

    @Test
    void aConvertedHoldCannotBeConvertedTwice() {
        InventoryReservation hold = cartHold();
        hold.converted(order, orderLine, UUID.randomUUID(), null);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> hold.converted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null));

        assertEquals("RESERVATION_STATE_CONFLICT", conflict.code());
        assertEquals(order, hold.convertedOrderId());
    }

    @Test
    void anOrderOwnedHoldIsNotConvertible() {
        InventoryReservation owned = InventoryReservation.create(store, order, Instant.now().plusSeconds(900),
                lines());

        assertThrows(ConflictException.class,
                () -> owned.converted(order, orderLine, UUID.randomUUID(), null));
        assertEquals(ReservationStatus.ACTIVE, owned.status());
    }

    @Test
    void aConsumedHoldRecordsTheActingUser() {
        InventoryReservation owned = InventoryReservation.create(store, order, Instant.now().plusSeconds(900),
                lines());
        UUID actor = UUID.randomUUID();

        owned.consumed(actor);

        assertEquals(ReservationStatus.CONSUMED, owned.status());
        assertEquals(actor, owned.consumedBy());
    }

    private InventoryReservation cartHold() {
        return InventoryReservation.forCart(store, cart, cartItem, Instant.now().plusSeconds(900),
                quantities(beans, "18", milk, "180"), Map.of(beans, "G", milk, "ML"), null, null, "VARIANT", "key");
    }

    private java.util.List<InventoryReservationLine> lines() {
        return java.util.List.of(new InventoryReservationLine(beans, new BigDecimal("18"), "G"));
    }

    private static Map<UUID, BigDecimal> quantities(UUID first, String firstQty, UUID second, String secondQty) {
        Map<UUID, BigDecimal> quantities = new LinkedHashMap<>();
        quantities.put(first, new BigDecimal(firstQty));
        quantities.put(second, new BigDecimal(secondQty));
        return quantities;
    }
}
