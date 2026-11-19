package com.smartcoffee.modules.recipe.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One ingredient requirement of a {@link RecipeVersion}. Slice 2A extends this
 * line in place with unit / scaling / display / optional / notes metadata while
 * preserving the two frozen values checkout depends on: {@code ingredientId} and
 * the {@link #demand(int)} formula.
 *
 * <p>{@link #demand(int)} keeps its exact historical meaning — per produced unit
 * the line consumes {@code quantity_required_base * productQuantity *
 * (1 + wastage%)}. That is FIXED scaling and is yield-independent, so existing
 * checkout output is unchanged regardless of the new {@code scalingRule} column
 * (which is only interpreted by Slice 2B planning).
 */
@Entity
@Table(schema = "recipe", name = "recipe_version_lines")
public class RecipeVersionLine {
    @Id
    @Column(name = "recipe_version_line_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_version_id", nullable = false)
    private RecipeVersion recipe;
    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;
    @Column(name = "quantity_required_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityRequiredBase;
    @Column(name = "wastage_percent", nullable = false, precision = 9, scale = 6)
    private BigDecimal wastagePercent;
    // --- Slice 2A extension columns (V042) ---
    @Column(name = "unit_id")
    private UUID unitId;
    @Enumerated(EnumType.STRING)
    @Column(name = "scaling_rule", nullable = false, length = 30)
    private RecipeScalingRule scalingRule;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "optional", nullable = false)
    private boolean optional;
    @Column(name = "notes")
    private String notes;

    protected RecipeVersionLine() {
    }

    public static RecipeVersionLine create(UUID ingredientId, BigDecimal quantityRequiredBase,
            BigDecimal wastagePercent, UUID unitId, RecipeScalingRule scalingRule,
            int displayOrder, boolean optional, String notes) {
        if (ingredientId == null) {
            throw new IllegalArgumentException("Recipe line requires an ingredient");
        }
        if (quantityRequiredBase == null || quantityRequiredBase.signum() <= 0) {
            throw new IllegalArgumentException("Quantity required must be greater than zero");
        }
        var w = wastagePercent == null ? BigDecimal.ZERO : wastagePercent;
        if (w.signum() < 0 || w.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Wastage percent must be between 0 and 100");
        }
        var l = new RecipeVersionLine();
        l.id = UUID.randomUUID();
        l.ingredientId = ingredientId;
        l.quantityRequiredBase = quantityRequiredBase;
        l.wastagePercent = w;
        l.unitId = unitId;
        l.scalingRule = scalingRule == null ? RecipeScalingRule.FIXED : scalingRule;
        l.displayOrder = displayOrder;
        l.optional = optional;
        l.notes = notes;
        return l;
    }

    /** Sets the owning version when a line is attached to a parent aggregate. */
    void attachTo(RecipeVersion parent) {
        this.recipe = parent;
    }

    public UUID id() {
        return id;
    }

    public UUID ingredientId() {
        return ingredientId;
    }

    public BigDecimal quantityRequiredBase() {
        return quantityRequiredBase;
    }

    public BigDecimal wastagePercent() {
        return wastagePercent;
    }

    public UUID unitId() {
        return unitId;
    }

    public RecipeScalingRule scalingRule() {
        return scalingRule;
    }

    public int displayOrder() {
        return displayOrder;
    }

    public boolean optional() {
        return optional;
    }

    public String notes() {
        return notes;
    }

    public BigDecimal demand(int productQuantity) {
        return quantityRequiredBase.multiply(BigDecimal.valueOf(productQuantity))
                .multiply(BigDecimal.ONE.add(wastagePercent.movePointLeft(2)));
    }
}
