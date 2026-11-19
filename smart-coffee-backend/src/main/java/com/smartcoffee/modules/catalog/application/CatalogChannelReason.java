package com.smartcoffee.modules.catalog.application;

/**
 * Channel-layer publication/schedule reason codes (Slice 3B, spec §20). Kept in a
 * SEPARATE enum from the frozen Slice 3A {@code AvailabilityReason} so the channel
 * policy verdict stays independently traceable before the two collections are
 * merged into one stable {@code reasonCodes} list on the catalog response (spec §5).
 *
 * <p>Codes are stable wire strings equal to {@link #name()}.
 */
public enum CatalogChannelReason {
    /** No publication exists (or it is explicitly hidden) for this channel. */
    CHANNEL_NOT_PUBLISHED,
    /** The channel row exists but {@code visible == false}. */
    CHANNEL_HIDDEN,
    /** Visible on the channel, but the channel's own {@code orderable == false} gate is off. */
    CHANNEL_NOT_ORDERABLE,
    /** The channel is currently outside its weekly schedule window. */
    CHANNEL_OUTSIDE_SCHEDULE,
    /** The channel is currently outside its absolute publication window. */
    CHANNEL_OUTSIDE_PUBLICATION_WINDOW;

    public String code() {
        return name();
    }
}
