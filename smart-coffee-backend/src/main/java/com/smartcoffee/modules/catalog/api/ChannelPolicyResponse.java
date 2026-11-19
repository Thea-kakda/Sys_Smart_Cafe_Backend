package com.smartcoffee.modules.catalog.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Effective channel publication policy for management read (Slice 3B, spec §18).
 * {@code inherited} is true when no explicit row exists and the channel falls back
 * to the legacy default (visible/orderable per channel inheritance): the effective
 * flags still report what the catalog would apply today.
 */
public record ChannelPolicyResponse(
        UUID channelId,
        String channel,
        boolean inherited,
        boolean visible,
        boolean orderable,
        String displayName,
        String displayDescription,
        int displayOrder,
        Instant availableFrom,
        Instant availableUntil,
        String timezone,
        long version) {
}
