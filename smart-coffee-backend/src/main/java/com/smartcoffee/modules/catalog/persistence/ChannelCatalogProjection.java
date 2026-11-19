package com.smartcoffee.modules.catalog.persistence;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One visibility-filtered, channel-projected catalog row for a single page
 * (Slice 3B). Combines the legacy store-product projection with the effective
 * channel publication policy resolved by the {@code catalog.product_channels}
 * LEFT JOIN. {@code channelId} is {@code null} when no explicit channel row
 * exists and the legacy visibility default was inherited (spec §8) — in that case
 * there are no channel schedules and no display overrides.
 *
 * <p>{@code channelVisible} is the effective visibility used for membership
 * (always {@code true} for returned rows, since hidden products are filtered out
 * before pagination). {@code channelOrderable} is the channel's own gate; it is
 * merged with the Slice 3A availability verdict in the service, never here.
 */
public interface ChannelCatalogProjection {
    UUID getProductId();

    String getProductCode();

    UUID getCategoryId();

    String getCategoryName();

    String getProductName();

    String getDescription();

    String getImageObjectKey();

    BigDecimal getPrice();

    String getCurrencyCode();

    boolean getEffectiveAvailable();

    int getPreparationMinutes();

    boolean getCustomizable();

    UUID getChannelId();

    boolean getChannelVisible();

    boolean getChannelOrderable();

    String getChannelDisplayName();

    String getChannelDisplayDescription();

    Integer getChannelDisplayOrder();
}
