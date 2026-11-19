package com.smartcoffee.modules.catalog.api;

import java.time.Instant;

/**
 * Manual sold-out control (Slice 3B, spec §6/§18). Maps directly onto the existing
 * store-product fields (no new source of truth): {@code available=false} is a
 * standing sold-out; {@code available=true} with a future {@code soldOutUntil} is a
 * temporary one; {@code soldOutUntil=null} clears a window. {@code expectedVersion}
 * is the store-product row version (409 STORE_PRODUCT_VERSION_CONFLICT when stale).
 */
public record ManualSoldOutRequest(
        boolean available,
        Instant soldOutUntil,
        long expectedVersion) {
}
