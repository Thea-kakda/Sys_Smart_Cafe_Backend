package com.smartcoffee.modules.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Store-scoped, channel-scoped publication policy for one product (Slice 3B, spec §3).
 * This owns ONLY the per-channel presentation/visibility policy:
 * {@code visible}, {@code orderable}, display name/description overrides, display
 * ordering, an optional publication window and an optional timezone reference.
 *
 * <p>It deliberately does NOT own product master data (price, active flag, category,
 * recipe) nor the store membership / manual sold-out lever, which stay on
 * {@code catalog.products} and {@code catalog.store_products}. Visibility is kept
 * separate from orderability (spec §4): a product can be {@code visible} while
 * {@code orderable == false} (sold out, off-schedule, out of stock).
 *
 * <p>Optimistic locking via {@code version} mirrors the rest of the catalog. The id
 * is application-assigned (no DB sequence), consistent with every other catalog
 * aggregate.
 */
@Entity
@Table(schema = "catalog", name = "product_channels")
public class ProductChannel {

    @Id
    @Column(name = "product_channel_id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;
    @Column(nullable = false)
    private boolean visible;
    @Column(nullable = false)
    private boolean orderable;
    @Column(name = "display_name", length = 150)
    private String displayName;
    @Column(name = "display_description", columnDefinition = "text")
    private String displayDescription;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "available_from")
    private Instant availableFrom;
    @Column(name = "available_until")
    private Instant availableUntil;
    @Column(length = 64)
    private String timezone;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "updated_by")
    private UUID updatedBy;
    @Version
    @Column(nullable = false)
    private long version;

    protected ProductChannel() {
    }

    /**
     * Creates an explicit publication row. New channels default to hidden
     * ({@code visible == false}) unless the caller opts in, so creating a
     * configuration never silently publishes a product. The window, when both
     * bounds are supplied, must be ordered; that invariant is also enforced by the
     * database CHECK.
     */
    public static ProductChannel create(UUID storeId, UUID productId, Channel channel, boolean visible,
            boolean orderable, String displayName, String displayDescription, int displayOrder,
            Instant availableFrom, Instant availableUntil, String timezone, UUID actor) {
        if (storeId == null || productId == null || channel == null) {
            throw new IllegalArgumentException("Store, product and channel are required");
        }
        if (displayOrder < 0) {
            throw new IllegalArgumentException("Display order must be non-negative");
        }
        if (availableFrom != null && availableUntil != null && !availableFrom.isBefore(availableUntil)) {
            throw new IllegalArgumentException("availableFrom must precede availableUntil");
        }
        var pc = new ProductChannel();
        pc.id = UUID.randomUUID();
        pc.storeId = storeId;
        pc.productId = productId;
        pc.channel = channel;
        pc.visible = visible;
        pc.orderable = orderable;
        pc.displayName = blankToNull(displayName);
        pc.displayDescription = blankToNull(displayDescription);
        pc.displayOrder = displayOrder;
        pc.availableFrom = availableFrom;
        pc.availableUntil = availableUntil;
        pc.timezone = blankToNull(timezone);
        var now = Instant.now();
        pc.createdAt = now;
        pc.updatedAt = now;
        pc.createdBy = actor;
        pc.updatedBy = actor;
        return pc;
    }

    /** Full-replacement configuration update; required identity fields are never reset. */
    public void reconfigure(boolean visible, boolean orderable, String displayName, String displayDescription,
            int displayOrder, Instant availableFrom, Instant availableUntil, String timezone, UUID actor) {
        if (displayOrder < 0) {
            throw new IllegalArgumentException("Display order must be non-negative");
        }
        if (availableFrom != null && availableUntil != null && !availableFrom.isBefore(availableUntil)) {
            throw new IllegalArgumentException("availableFrom must precede availableUntil");
        }
        this.visible = visible;
        this.orderable = orderable;
        this.displayName = blankToNull(displayName);
        this.displayDescription = blankToNull(displayDescription);
        this.displayOrder = displayOrder;
        this.availableFrom = availableFrom;
        this.availableUntil = availableUntil;
        this.timezone = blankToNull(timezone);
        this.updatedAt = Instant.now();
        this.updatedBy = actor;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Records that the weekly schedule collection owned by this aggregate was replaced (Slice 3B.2
     * §9). Schedules live in a child table, so a replacement would otherwise leave the channel row
     * untouched and its {@code @Version} unmoved: two writers holding the same
     * {@code expectedVersion} would both pass the guard and overwrite each other's windows, which
     * contradicts the documented meaning of {@code ReplaceSchedulesRequest.expectedVersion}. Touching
     * the aggregate makes JPA advance the lock so the replacement is genuinely versioned.
     */
    public void scheduleReplaced(UUID actor) {
        this.updatedAt = Instant.now();
        this.updatedBy = actor;
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID productId() {
        return productId;
    }

    public Channel channel() {
        return channel;
    }

    public boolean visible() {
        return visible;
    }

    public boolean orderable() {
        return orderable;
    }

    public String displayName() {
        return displayName;
    }

    public String displayDescription() {
        return displayDescription;
    }

    public int displayOrder() {
        return displayOrder;
    }

    public Instant availableFrom() {
        return availableFrom;
    }

    public Instant availableUntil() {
        return availableUntil;
    }

    public String timezone() {
        return timezone;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public long version() {
        return version;
    }
}
