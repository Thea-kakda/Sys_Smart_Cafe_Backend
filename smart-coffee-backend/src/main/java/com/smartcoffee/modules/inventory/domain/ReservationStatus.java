package com.smartcoffee.modules.inventory.domain;

/**
 * Lifecycle of a held reservation.
 *
 * <p>ACTIVE holds stock. CONVERTED is a cart-owned hold whose demand has been
 * taken over by an order at checkout (Slice 2D): the order reservation now owns
 * the same held quantity, so the row is kept as provenance and never released
 * again. CONSUMED is the settlement outcome (stock has left the building),
 * RELEASED returns the stock (cart item removed, cart cleared, order cancelled,
 * payment failure), EXPIRED is the deadline path, CANCELLED is an administrative
 * cancellation of a cart-owned hold.
 *
 * <p>CONVERTED and CONSUMED are distinct on purpose: only CONSUMED means
 * {@code current_stock} moved, CONVERTED moved no balance at all.
 */
public enum ReservationStatus {
    ACTIVE,
    CONVERTED,
    CONSUMED,
    RELEASED,
    EXPIRED,
    CANCELLED
}
