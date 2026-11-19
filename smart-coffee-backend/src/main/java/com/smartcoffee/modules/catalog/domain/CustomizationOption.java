package com.smartcoffee.modules.catalog.domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(schema="catalog", name="customization_options", uniqueConstraints=@UniqueConstraint(name="uk_option_group_code", columnNames={"group_id","option_code"}))
public class CustomizationOption {
    @Id @Column(name="option_id") private UUID id;
    @Column(name="group_id", nullable=false) private UUID groupId;
    @Column(name="option_code", nullable=false, length=50) private String code;
    @Column(name="option_name", nullable=false, length=100) private String name;
    @Column(name="additional_price", nullable=false, precision=14, scale=4) private BigDecimal additionalPrice;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Column(nullable = false) private boolean active;
    // V039 additive columns. All optional / default NONE so pre-existing
    // options keep working with zero stock movement (price only). See
    // migration ck_option_effect_shape for type-conditional requirements.
    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_effect_type", nullable = false, length = 15) private InventoryEffectType inventoryEffectType = InventoryEffectType.NONE;
    @Column(name = "target_ingredient_id") private UUID targetIngredientId;
    // V041 additive column. Preferred source/target split:
    //   REMOVE, MULTIPLY -> source_ingredient_id carries the affected ingredient.
    //   REPLACE          -> both are populated (source is removed, target is added).
    //   ADD              -> only target is populated.
    // Legacy rows written under V039 (where target acted as the affected
    // ingredient for REMOVE/MULTIPLY) are backfilled by V041.
    @Column(name = "source_ingredient_id") private UUID sourceIngredientId;
    @Column(name = "effect_quantity", precision = 14, scale = 4) private BigDecimal effectQuantity;
    @Column(name = "effect_multiplier", precision = 7, scale = 4) private BigDecimal effectMultiplier;
    @Column(name = "replaces_option_id") private UUID replacesOptionId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    protected CustomizationOption() {}
    public UUID id(){return id;}
    public UUID groupId(){return groupId;}
    public String code(){return code;}
    public String name(){return name;}
    public BigDecimal additionalPrice(){return additionalPrice;}
    public int displayOrder(){return displayOrder;}
    public boolean active(){return active;}
    public InventoryEffectType inventoryEffectType(){return inventoryEffectType;}
    public UUID targetIngredientId(){return targetIngredientId;}
    public UUID sourceIngredientId(){return sourceIngredientId;}
    public BigDecimal effectQuantity(){return effectQuantity;}
    public BigDecimal effectMultiplier(){return effectMultiplier;}
    public UUID replacesOptionId(){return replacesOptionId;}
    public long version(){return version;}
    /**
     * Applies (or clears with null args + NONE type) the inventory effect on
     * this option. Shape rules are validated here so callers cannot persist
     * a partial effect that would fail ck_option_effect_shape at the DB level.
     */
    public void applyInventoryEffect(InventoryEffectType type, UUID sourceIngredientId, UUID targetIngredientId,
            BigDecimal effectQuantity, BigDecimal effectMultiplier, UUID replacesOptionId) {
        this.inventoryEffectType = (type == null) ? InventoryEffectType.NONE : type;
        this.sourceIngredientId = null;
        this.targetIngredientId = null;
        this.effectQuantity = null;
        this.effectMultiplier = null;
        this.replacesOptionId = null;
        switch (this.inventoryEffectType) {
            case NONE -> { /* nothing to set */ }
            case ADD -> {
                if (targetIngredientId == null) throw new IllegalArgumentException("ADD requires target ingredient");
                if (effectQuantity == null || effectQuantity.signum() <= 0) throw new IllegalArgumentException("ADD requires positive effect_quantity");
                if (sourceIngredientId != null) throw new IllegalArgumentException("ADD must not declare a source ingredient");
                this.targetIngredientId = targetIngredientId;
                this.effectQuantity = effectQuantity;
            }
            case REMOVE -> {
                if (sourceIngredientId == null) throw new IllegalArgumentException("REMOVE requires source ingredient");
                if (effectQuantity != null && effectQuantity.signum() <= 0) throw new IllegalArgumentException("REMOVE effect_quantity must be positive when provided");
                if (targetIngredientId != null) throw new IllegalArgumentException("REMOVE must not declare a target ingredient");
                this.sourceIngredientId = sourceIngredientId;
                this.effectQuantity = effectQuantity;
            }
            case REPLACE -> {
                if (sourceIngredientId == null) throw new IllegalArgumentException("REPLACE requires source ingredient");
                if (targetIngredientId == null) throw new IllegalArgumentException("REPLACE requires target ingredient");
                if (sourceIngredientId.equals(targetIngredientId)) throw new IllegalArgumentException("REPLACE source and target must differ");
                if (effectQuantity != null && effectQuantity.signum() <= 0) throw new IllegalArgumentException("REPLACE effect_quantity must be positive when provided");
                this.sourceIngredientId = sourceIngredientId;
                this.targetIngredientId = targetIngredientId;
                this.effectQuantity = effectQuantity;
            }
            case MULTIPLY -> {
                if (sourceIngredientId == null) throw new IllegalArgumentException("MULTIPLY requires source ingredient");
                if (effectMultiplier == null) throw new IllegalArgumentException("MULTIPLY requires effect_multiplier");
                if (effectMultiplier.signum() < 0) throw new IllegalArgumentException("MULTIPLY effect_multiplier must be non-negative");
                if (targetIngredientId != null) throw new IllegalArgumentException("MULTIPLY must not declare a target ingredient");
                this.sourceIngredientId = sourceIngredientId;
                this.effectMultiplier = effectMultiplier;
            }
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Round 2 (§7) master management: an option is created active, with the
     * effect columns left at their NONE defaults — effect configuration stays on
     * the dedicated {@link #applyInventoryEffect} path so there is exactly one
     * write surface per concern. Code and group membership are immutable after
     * creation (order references and attachment allow-lists key on the id).
     */
    public static CustomizationOption create(UUID groupId, String code, String name,
            BigDecimal additionalPrice, int displayOrder) {
        if (groupId == null) {
            throw new IllegalArgumentException("Option requires a group id");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Option code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Option name is required");
        }
        validateValue(additionalPrice, displayOrder);
        CustomizationOption o = new CustomizationOption();
        o.id = UUID.randomUUID();
        o.groupId = groupId;
        o.code = code.trim();
        o.name = name.trim();
        o.additionalPrice = additionalPrice;
        o.displayOrder = displayOrder;
        o.active = true;
        Instant now = Instant.now();
        o.createdAt = now;
        o.updatedAt = now;
        return o;
    }

    /** Name, price delta and order; the effect payload is untouched here. */
    public void updateValue(String name, BigDecimal additionalPrice, int displayOrder) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Option name is required");
        }
        validateValue(additionalPrice, displayOrder);
        this.name = name.trim();
        this.additionalPrice = additionalPrice;
        this.displayOrder = displayOrder;
        this.updatedAt = Instant.now();
    }

    public void markActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    /** Reorder-only write surface: touches the display position and nothing else. */
    public void reorderTo(int displayOrder) {
        if (displayOrder < 0) {
            throw new IllegalArgumentException("Display order must be non-negative");
        }
        this.displayOrder = displayOrder;
        this.updatedAt = Instant.now();
    }

    private static void validateValue(BigDecimal price, int displayOrder) {
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("Additional price must be non-negative");
        }
        if (displayOrder < 0) {
            throw new IllegalArgumentException("Display order must be non-negative");
        }
    }
}

