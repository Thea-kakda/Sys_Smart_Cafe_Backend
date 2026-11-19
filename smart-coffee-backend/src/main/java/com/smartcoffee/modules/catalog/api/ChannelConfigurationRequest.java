package com.smartcoffee.modules.catalog.api;

import java.time.Instant;

/**
 * Upsert payload for a store/product channel publication (Slice 3B, spec §18).
 * {@code expectedVersion} implements optimistic locking: it must equal the current
 * row version (or be {@code null}/{@code 0} when creating an absent row); a stale
 * value returns 409 PRODUCT_CHANNEL_VERSION_CONFLICT. Display name/description are
 * optional per-channel overrides; a blank value clears the override (inherit master).
 * {@code timezone} is an optional IANA id overriding the store timezone for
 * schedule evaluation; blank/null inherits the store timezone.
 */
public record ChannelConfigurationRequest(
        boolean visible,
        boolean orderable,
        String displayName,
        String displayDescription,
        Integer displayOrder,
        Instant availableFrom,
        Instant availableUntil,
        String timezone,
        Long expectedVersion) {
}
