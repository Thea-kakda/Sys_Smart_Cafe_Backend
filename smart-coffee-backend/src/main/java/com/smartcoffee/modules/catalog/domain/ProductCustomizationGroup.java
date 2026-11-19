package com.smartcoffee.modules.catalog.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Product &lt;-&gt; customization-group attachment row.
 *
 * <p><b>Round 1 (V003) storage contract.</b> The table began as four columns —
 * the composite primary key {@code (product_id, group_id)} plus the two
 * per-product overrides {@code required} and {@code display_order} — with no
 * surrogate id, no timestamp pair and no version, so every other effective value
 * was inherited from the master {@link CustomizationGroup} /
 * {@link CustomizationOption} and the optimistic-lock token was the owning
 * {@link Product#version()} (advanced through
 * {@link Product#childCollectionChanged()}).
 *
 * <p><b>Round 2 (V049) storage contract.</b> V049 adds the columns Round 1
 * promised: an independent relationship {@code version} (the capabilities
 * {@code relationshipVersionSource} becomes {@code RELATIONSHIP}), per-product
 * {@code minimum_selections}/{@code maximum_selections} overrides (NULL means
 * inherit the master group), a per-product {@code default_option_id}, an
 * {@code active} state flag and the V048 audit pair. The effective per-product
 * allowed-option subset lives in the child {@link ProductCustomizationOption}
 * rows, not here.
 *
 * <p>Rules that cannot be a single-table CHECK — SINGLE capped at max&nbsp;1,
 * "default is inside the effective allowed set", "maximum &gt;= the inherited
 * minimum" — are enforced by the application service and covered by tests,
 * exactly as the migration comment stipulates. This entity only guards value
 * shapes it owns locally (non-negative ordering, non-negative limits).
 */
@Entity
@Table(schema = "catalog", name = "product_customization_groups")
public class ProductCustomizationGroup {
    @EmbeddedId private ProductCustomizationGroupId id;
    @Column(nullable = false) private boolean required;
    @Column(name = "display_order", nullable = false) private int displayOrder;

    // ---- V049 (Round 2) additive columns -----------------------------------
    @Version @Column(nullable = false) private long version;
    @Column(name = "minimum_selections") private Integer minimumSelections;
    @Column(name = "maximum_selections") private Integer maximumSelections;
    @Column(name = "default_option_id") private UUID defaultOptionId;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "updated_by") private UUID updatedBy;

    protected ProductCustomizationGroup() {}

    /**
     * Attaches an existing master group to an existing product. Referential
     * existence (product, group, group active) is validated in the application
     * service where the repositories live; the domain only guards the value
     * shapes so no caller can write a negative ordering.
     */
    public static ProductCustomizationGroup create(UUID productId, UUID groupId, boolean required, int displayOrder) {
        var attachment = new ProductCustomizationGroup();
        attachment.id = new ProductCustomizationGroupId(productId, groupId);
        attachment.configure(required, displayOrder);
        return attachment;
    }

    /**
     * Re-applies the two Round 1 writable per-product fields. This is an
     * in-place update (never delete-and-reinsert) so the composite key — and
     * therefore any historical reference to this attachment — stays stable. The
     * Round 2 override fields are left untouched by this call, preserving an
     * already-configured min/max/default when only the base flags change.
     */
    public void configure(boolean required, int displayOrder) {
        if (displayOrder < 0) {
            throw new IllegalArgumentException("Display order must be non-negative");
        }
        this.required = required;
        this.displayOrder = displayOrder;
    }

    /**
     * Applies the full Round 2 override set in place. {@code minimumSelections}
     * and {@code maximumSelections} accept {@code null} to mean "inherit the
     * master group"; {@code defaultOptionId} accepts {@code null} to mean "no
     * default". Cross-field rules (SINGLE cap, default-in-allowed-set, max &gt;=
     * inherited min) are validated by the service, which alone can see the
     * master group and the option allow-list; here we guard only the locally
     * visible value shapes.
     */
    public void configureOverrides(boolean required, int displayOrder, Integer minimumSelections,
            Integer maximumSelections, UUID defaultOptionId) {
        if (displayOrder < 0) {
            throw new IllegalArgumentException("Display order must be non-negative");
        }
        if (minimumSelections != null && minimumSelections < 0) {
            throw new IllegalArgumentException("Minimum selections must be non-negative");
        }
        if (maximumSelections != null && maximumSelections < 0) {
            throw new IllegalArgumentException("Maximum selections must be non-negative");
        }
        if (minimumSelections != null && maximumSelections != null && maximumSelections < minimumSelections) {
            throw new IllegalArgumentException("Maximum selections must be >= minimum selections");
        }
        this.required = required;
        this.displayOrder = displayOrder;
        this.minimumSelections = minimumSelections;
        this.maximumSelections = maximumSelections;
        this.defaultOptionId = defaultOptionId;
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

    public UUID productId() {return id.productId();}
    public UUID groupId() {return id.groupId();}
    public ProductCustomizationGroupId id() {return id;}
    public boolean required() {return required;}
    public int displayOrder() {return displayOrder;}
    public long version() {return version;}
    public Integer minimumSelections() {return minimumSelections;}
    public Integer maximumSelections() {return maximumSelections;}
    public UUID defaultOptionId() {return defaultOptionId;}
    public boolean active() {return active;}
    public Instant createdAt() {return createdAt;}
    public Instant updatedAt() {return updatedAt;}
    public UUID createdBy() {return createdBy;}
    public UUID updatedBy() {return updatedBy;}

    /** Records who last wrote the override (kept alongside the row's audit pair). */
    public void markUpdatedBy(UUID actorId) {
        this.updatedBy = actorId;
        if (createdBy == null) {
            this.createdBy = actorId;
        }
    }
}
