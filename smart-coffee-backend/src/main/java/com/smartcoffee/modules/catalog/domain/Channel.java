package com.smartcoffee.modules.catalog.domain;

/**
 * Controlled catalog publication channel (Slice 3B, spec §3). The persisted value is
 * the enum name (see {@code ck_product_channel_enum} in V048). POS, CUSTOMER_WEB and
 * CUSTOMER_APP are the surfaces integrated in Slice 3B; QR_MENU, KIOSK and DELIVERY
 * are declared so the model and constraint already accept them, but they stay
 * hidden and are NOT wired to any public endpoint until a later slice.
 *
 * <p>Design contract: future channels are added by extending this enum and the
 * migration CHECK list together. The catalog read uses {@link #inheritsLegacyVisibility()}
 * to define the deterministic "no explicit channel row yet" default.
 */
public enum Channel {
    POS,
    CUSTOMER_WEB,
    CUSTOMER_APP,
    QR_MENU,
    KIOSK,
    DELIVERY;

    /**
     * Whether an ABSENT channel configuration inherits the legacy store-product
     * visibility/ordering behavior (so pre-existing products never disappear and
     * post-migration products behave exactly as before until published). True for
     * the three Slice 3B surfaces; false for the deferred channels, which are
     * hidden until an operator explicitly publishes them (spec §8).
     */
    public boolean inheritsLegacyVisibility() {
        return this == POS || this == CUSTOMER_WEB || this == CUSTOMER_APP;
    }

    /** Parses a controlled channel name; throws for anything outside the enum. */
    public static Channel from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Channel is required");
        }
        return Channel.valueOf(value.trim().toUpperCase());
    }
}
