package com.smartcoffee.modules.recipe.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Product-scoped recipe version — the authoritative production plan used by
 * {@code RecipeDemandService} during checkout. Slice 2A evolves this aggregate
 * IN PLACE (no new master table): it now carries an expanded lifecycle
 * (DRAFT / ACTIVE / SUPERSEDED / ARCHIVED), yield + audit metadata, and a
 * correctly mapped optimistic-lock {@code version} column.
 *
 * <p>Identity of an existing row is never rewritten: {@code id} is assigned at
 * creation and there is no setter. ACTIVE / SUPERSEDED / ARCHIVED versions are
 * immutable; only DRAFT versions accept edits, and activation is the single
 * controlled transition that flips DRAFT -> ACTIVE (superseding the previous
 * ACTIVE version in the same transaction, orchestrated by the service).
 */
@Entity
@Table(schema = "recipe", name = "recipe_versions")
public class RecipeVersion {
    @Id
    @Column(name = "recipe_version_id")
    private UUID id;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    @Column(name = "version_number", nullable = false)
    private int versionNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecipeStatus status;
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
    @Column(name = "effective_to")
    private Instant effectiveTo;
    // --- Slice 2A extension columns (V042) ---
    @Column(name = "yield_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal yieldQuantity;
    @Column(name = "yield_unit_id")
    private UUID yieldUnitId;
    @Column(name = "notes")
    private String notes;
    @Column(name = "activated_at")
    private Instant activatedAt;
    @Column(name = "activated_by")
    private UUID activatedBy;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "updated_by")
    private UUID updatedBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;
    @OneToMany(mappedBy = "recipe", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RecipeVersionLine> lines = new ArrayList<>();

    protected RecipeVersion() {
    }

    /**
     * Creates a new DRAFT version for a product. The caller (service) supplies a
     * reserved, monotonically increasing {@code versionNumber} within the product.
     * Lines are attached afterwards via {@link #replaceLines} while still DRAFT.
     */
    public static RecipeVersion createDraft(UUID productId, int versionNumber, BigDecimal yieldQuantity,
            UUID yieldUnitId, String notes, UUID actorId) {
        if (productId == null) {
            throw new IllegalArgumentException("Recipe version requires a product");
        }
        if (versionNumber <= 0) {
            throw new IllegalArgumentException("Version number must be positive");
        }
        if (yieldQuantity != null && yieldQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Yield quantity must be greater than zero");
        }
        var v = new RecipeVersion();
        v.id = UUID.randomUUID();
        v.productId = productId;
        v.versionNumber = versionNumber;
        v.status = RecipeStatus.DRAFT;
        v.yieldQuantity = yieldQuantity == null ? BigDecimal.ONE : yieldQuantity;
        v.yieldUnitId = yieldUnitId;
        v.notes = notes;
        var now = Instant.now();
        v.effectiveFrom = now;
        v.createdBy = actorId;
        v.updatedBy = actorId;
        v.createdAt = now;
        v.updatedAt = now;
        return v;
    }

    public UUID id() {
        return id;
    }

    public UUID productId() {
        return productId;
    }

    public int versionNumber() {
        return versionNumber;
    }

    public RecipeStatus status() {
        return status;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant effectiveTo() {
        return effectiveTo;
    }

    public BigDecimal yieldQuantity() {
        return yieldQuantity;
    }

    public UUID yieldUnitId() {
        return yieldUnitId;
    }

    public String notes() {
        return notes;
    }

    public Instant activatedAt() {
        return activatedAt;
    }

    public UUID activatedBy() {
        return activatedBy;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public List<RecipeVersionLine> lines() {
        return List.copyOf(lines);
    }

    /** Only DRAFT versions may be edited; everything else is an immutable snapshot. */
    public boolean isEditable() {
        return status == RecipeStatus.DRAFT;
    }

    /** Replaces the version's lines. Allowed only while DRAFT. Re-attaches children. */
    public void replaceLines(List<RecipeVersionLine> newLines) {
        requireEditable();
        lines.clear();
        if (newLines != null) {
            for (RecipeVersionLine line : newLines) {
                line.attachTo(this);
                lines.add(line);
            }
        }
        touch();
    }

    /** Updates mutable header metadata. Allowed only while DRAFT. */
    public void updateMeta(BigDecimal yieldQuantity, UUID yieldUnitId, String notes, UUID actorId) {
        requireEditable();
        if (yieldQuantity != null && yieldQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Yield quantity must be greater than zero");
        }
        if (yieldQuantity != null) {
            this.yieldQuantity = yieldQuantity;
        }
        this.yieldUnitId = yieldUnitId;
        this.notes = notes;
        this.updatedBy = actorId;
        touch();
    }

    /** DRAFT -> ACTIVE. Records the activation actor/time and opens the effective window. */
    public void markActive(UUID actorId, Instant now) {
        if (status != RecipeStatus.DRAFT) {
            throw new IllegalStateException("Only a DRAFT recipe version can be activated");
        }
        if (lines.isEmpty()) {
            throw new IllegalStateException("A recipe version with no lines cannot be activated");
        }
        this.status = RecipeStatus.ACTIVE;
        this.effectiveFrom = now;
        this.effectiveTo = null;
        this.activatedAt = now;
        this.activatedBy = actorId;
        this.updatedBy = actorId;
        touch();
    }

    /** ACTIVE -> SUPERSEDED (immutable, still readable). */
    public void markSuperseded(Instant now) {
        this.status = RecipeStatus.SUPERSEDED;
        this.effectiveTo = now;
        touch();
    }

    /** -> ARCHIVED (immutable, cannot be assigned). */
    public void markArchived() {
        this.status = RecipeStatus.ARCHIVED;
        touch();
    }

    private void requireEditable() {
        if (!isEditable()) {
            throw new IllegalStateException("Only a DRAFT recipe version can be edited");
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
