package com.smartcoffee.modules.catalog.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Product-specific option allow-list row (V049). One row per
 * {@code (product_id, group_id, option_id)} recording whether that option is
 * allowed for this product, an optional per-product {@code price_override}, and
 * a per-product {@code display_order}. The effective allowed set a customer/POS
 * sees is {@code allowed = true AND active = true AND the master option is
 * active}; anything else is inherited from, or removed by, the master
 * {@link CustomizationOption}.
 *
 * <p>The V049 backfill seeds one allowed row (no price override, master display
 * order copied) for every ACTIVE option of every existing attachment, so the
 * effective configuration is unchanged the moment the migration lands. Rows for
 * options that later go inactive are retained (never deleted) so historical
 * references resolve; they simply drop out of the effective set.
 */
@Entity
@Table(schema = "catalog", name = "product_customization_options")
public class ProductCustomizationOption {
    @Id @Column(name = "product_customization_option_id") private UUID id;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "group_id", nullable = false) private UUID groupId;
    @Column(name = "option_id", nullable = false) private UUID optionId;
    @Column(nullable = false) private boolean allowed = true;
    @Column(name = "price_override", precision = 14, scale = 4) private BigDecimal priceOverride;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Version @Column(nullable = false) private long version;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "updated_by") private UUID updatedBy;

    protected ProductCustomizationOption() {}

    /** Seed one allowed row for an option of the attachment's group. */
    public static ProductCustomizationOption allow(UUID productId, UUID groupId, UUID optionId, int displayOrder) {
        var row = new ProductCustomizationOption();
        row.id = UUID.randomUUID();
        row.productId = productId;
        row.groupId = groupId;
        row.optionId = optionId;
        row.allowed = true;
        row.priceOverride = null;
        row.displayOrder = displayOrder;
        row.active = true;
        return row;
    }

    /** Re-applies the writable per-product option fields in place (never delete-and-reinsert). */
    public void configure(boolean allowed, BigDecimal priceOverride, int displayOrder) {
        if (displayOrder < 0) {
            throw new IllegalArgumentException("Display order must be non-negative");
        }
        if (priceOverride != null && priceOverride.signum() < 0) {
            throw new IllegalArgumentException("Price override must be non-negative");
        }
        this.allowed = allowed;
        this.priceOverride = priceOverride;
        this.displayOrder = displayOrder;
    }

    public void deactivate() {
        this.active = false;
    }

    @PrePersist
    void onTouch() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID id() {return id;}
    public UUID productId() {return productId;}
    public UUID groupId() {return groupId;}
    public UUID optionId() {return optionId;}
    public boolean allowed() {return allowed;}
    public BigDecimal priceOverride() {return priceOverride;}
    public int displayOrder() {return displayOrder;}
    public long version() {return version;}
    public boolean active() {return active;}
    public Instant createdAt() {return createdAt;}
    public Instant updatedAt() {return updatedAt;}
    public UUID createdBy() {return createdBy;}
    public UUID updatedBy() {return updatedBy;}

    public void markUpdatedBy(UUID actorId) {
        this.updatedBy = actorId;
        if (createdBy == null) {
            this.createdBy = actorId;
        }
    }
}
