package com.smartcoffee.modules.inventory.application;

/**
 * Stable reason vocabulary written on the reservation row ({@code release_reason})
 * and on the movement ledger ({@code stock_transactions.reason}). Keeping these
 * central means the audit trail is queryable by cause, not by free text.
 */
public final class ReservationReasons {

    /** A new cart line reserved its authoritative demand. */
    public static final String CART_ITEM_ADDED = "CART_ITEM_ADDED";
    /** Quantity went up: only the positive delta was reserved. */
    public static final String CART_QUANTITY_INCREASED = "CART_QUANTITY_INCREASED";
    /** Quantity went down: only the negative delta was released. */
    public static final String CART_QUANTITY_DECREASED = "CART_QUANTITY_DECREASED";
    /** Variant or customization change: per-ingredient delta applied. */
    public static final String CART_CONFIGURATION_CHANGED = "CART_CONFIGURATION_CHANGED";
    /** The cart line was removed. */
    public static final String CART_ITEM_REMOVED = "CART_ITEM_REMOVED";
    /** The cart was cleared. */
    public static final String CART_CLEARED = "CART_CLEARED";
    /** The cart expired and its holds were given back. */
    public static final String CART_EXPIRED = "CART_EXPIRED";
    /** The cart was cancelled administratively. */
    public static final String CART_CANCELLED = "CART_CANCELLED";
    /**
     * The order was settled, so its reservation was consumed (Slice 2D). This is
     * the one reason written on a {@code SALE_CONSUMPTION} row.
     */
    public static final String ORDER_SETTLED = "ORDER_SETTLED";
    /** The order was cancelled before its reservation was consumed. */
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    /** The payment window expired (or the attempt failed terminally). */
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    /**
     * A legacy cart line (pre-V045, no persisted hold) had its demand calculated
     * and reserved at checkout, inside the checkout transaction.
     */
    public static final String LEGACY_CHECKOUT_RESERVE = "LEGACY_CHECKOUT_RESERVE";
    /** Explicit administrative release of a cart hold. */
    public static final String ADMIN_RELEASE = "ADMIN_RELEASE";

    // ---- Slice 2E: reservation expiry worker + safe administrative release ----
    /**
     * The order reservation's OWN TTL elapsed with the order still unpaid, so the
     * expiry worker released the hold (spec §6). Distinct from
     * {@link #PAYMENT_FAILED}: the payment window is owned by the payment
     * expiration service; this names the reservation TTL sweeping ahead of it.
     */
    public static final String ORDER_RESERVATION_EXPIRED = "ORDER_RESERVATION_EXPIRED";
    /** An operator released an abandoned cart hold through the management API (§12). */
    public static final String ABANDONED_CART = "ABANDONED_CART";
    /** An operator released the hold of an already-cancelled order (§12). */
    public static final String CANCELLED_ORDER = "CANCELLED_ORDER";
    /** An operator released a reservation the worker should have expired (§12). */
    public static final String EXPIRED_RESERVATION = "EXPIRED_RESERVATION";
    /** A guarded reconciliation repair released/fixed stock (spec §10/§11). */
    public static final String DATA_RECOVERY = "DATA_RECOVERY";

    private ReservationReasons() {
    }
}
