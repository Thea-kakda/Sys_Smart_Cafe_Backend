package com.smartcoffee.modules.catalog.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "catalog", name = "products")
public class Product {
    @Id @Column(name = "product_id", nullable = false, updatable = false) private UUID id;
    @Column(name = "product_code", nullable = false, unique = true, length = 50) private String code;
    @Column(name = "category_id", nullable = false) private UUID categoryId;
    @Column(name = "product_name", nullable = false, length = 150) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "image_object_key") private String imageObjectKey;
    @Column(name = "base_price", nullable = false, precision = 14, scale = 4) private BigDecimal basePrice;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;
    @Column(name = "preparation_minutes", nullable = false) private int preparationMinutes;
    @Column(name = "tax_rate", nullable = false, precision = 9, scale = 6) private BigDecimal taxRate;
    @Column(nullable = false) private boolean customizable;
    @Column(nullable = false) private boolean active;
    // V039 additive columns. Defaults preserve pre-variant behaviour: an
    // existing product remains a STANDARD item made to order (no forced
    // recipe/inventory conversion) and continues to be sold through the
    // base-price path when it has zero variants.
    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 20) private ProductType productType = ProductType.STANDARD;
    @Enumerated(EnumType.STRING)
    @Column(name = "stock_mode", nullable = false, length = 20) private StockMode stockMode = StockMode.MADE_TO_ORDER;
    // Slice 2B.1: explicit product fallback recipe pointer (V044). Null means the
    // product declares no fallback; assignment (not status) selects the recipe.
    @Column(name = "active_recipe_version_id") private UUID activeRecipeVersionId;
    // Slice 5A: finished-good output inventory pointer (V051). Nullable — legacy
    // products remain readable/sellable without one; production planning
    // requires an explicit mapping and refuses with PRODUCTION_OUTPUT_ITEM_REQUIRED
    // otherwise (Decision 1). The variant pointer takes precedence when set.
    @Column(name = "output_ingredient_id") private UUID outputIngredientId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected Product() {}

    /**
     * Creates a new catalog product (master data). The id is assigned here because
     * the primary key is application-generated (no DB sequence). Basic invariants
     * are enforced in the domain; range/precision, currency and referential checks
     * live in the application service where the schema and repositories are known.
     */
    public static Product create(UUID categoryId, String code, String name, String description,
            BigDecimal basePrice, String currencyCode, int preparationMinutes, BigDecimal taxRate,
            boolean customizable, boolean active, String imageObjectKey) {
        if (categoryId == null) {
            throw new IllegalArgumentException("Category is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Product code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Product name is required");
        }
        if (basePrice == null || basePrice.signum() < 0) {
            throw new IllegalArgumentException("Base price must be non-negative");
        }
        if (taxRate == null || taxRate.signum() < 0) {
            throw new IllegalArgumentException("Tax rate must be non-negative");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException("Currency code is required");
        }
        var product = new Product();
        product.id = UUID.randomUUID();
        product.categoryId = categoryId;
        product.code = code.trim();
        product.name = name.trim();
        product.description = description;
        product.basePrice = basePrice;
        product.currencyCode = currencyCode.trim().toUpperCase();
        product.preparationMinutes = preparationMinutes;
        product.taxRate = taxRate;
        product.customizable = customizable;
        product.active = active;
        // Image key is set through the dedicated method so prefix ownership and
        // replacement invariants stay in one place.
        product.imageObjectKey = null;
        if (imageObjectKey != null && !imageObjectKey.isBlank()) {
            product.changeImage(imageObjectKey);
        }
        var now = Instant.now();
        product.createdAt = now;
        product.updatedAt = now;
        return product;
    }

    /**
     * Full-replacement of the mutable descriptive/pricing fields. Does NOT touch
     * the image (handled by {@link #changeImage}) or the audit columns other than
     * {@code updatedAt}. Active flag is part of the same business snapshot.
     */
    public void updateDetails(UUID categoryId, String code, String name, String description,
            BigDecimal basePrice, String currencyCode, int preparationMinutes, BigDecimal taxRate,
            boolean customizable, boolean active) {
        if (categoryId == null) {
            throw new IllegalArgumentException("Category is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Product code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Product name is required");
        }
        if (basePrice == null || basePrice.signum() < 0) {
            throw new IllegalArgumentException("Base price must be non-negative");
        }
        if (taxRate == null || taxRate.signum() < 0) {
            throw new IllegalArgumentException("Tax rate must be non-negative");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException("Currency code is required");
        }
        this.categoryId = categoryId;
        this.code = code.trim();
        this.name = name.trim();
        this.description = description;
        this.basePrice = basePrice;
        this.currencyCode = currencyCode.trim().toUpperCase();
        this.preparationMinutes = preparationMinutes;
        this.taxRate = taxRate;
        this.customizable = customizable;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    /**
     * Sets the private-bucket object key for the product image. Accepts a blank
     * value to clear the image. The caller (application layer) is responsible for
     * validating that the key belongs to the {@code products/} prefix; the domain
     * never accepts a URL here.
     */
    public void changeImage(String imageObjectKey) {
        this.imageObjectKey = (imageObjectKey == null || imageObjectKey.isBlank()) ? null : imageObjectKey.trim();
        this.updatedAt = Instant.now();
    }

    /**
     * Points the product fallback recipe at an assignable RecipeVersion of this
     * product. Ownership/assignability validation lives in the application
     * service; the domain only refuses a null assignment through this method
     * (use {@link #clearRecipeVersion()} to remove the fallback).
     */
    public void assignRecipeVersion(UUID recipeVersionId) {
        if (recipeVersionId == null) {
            throw new IllegalArgumentException("Use clearRecipeVersion() to remove the fallback recipe");
        }
        this.activeRecipeVersionId = recipeVersionId;
        this.updatedAt = Instant.now();
    }

    /** Removes the fallback recipe pointer (resolution then yields NONE unless a variant overrides). */
    public void clearRecipeVersion() {
        this.activeRecipeVersionId = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Points the product's finished-good output inventory item (Slice 5A).
     * Ownership/existence validation of the referenced ingredient is done in
     * the management service; the domain only refuses null through this method
     * (use {@link #clearOutputIngredient()} to remove the mapping).
     */
    public void assignOutputIngredient(UUID outputIngredientId) {
        if (outputIngredientId == null) {
            throw new IllegalArgumentException("Use clearOutputIngredient() to remove the mapping");
        }
        this.outputIngredientId = outputIngredientId;
        this.updatedAt = Instant.now();
    }

    /** Clears the finished-good output mapping (production planning then requires a variant override). */
    public void clearOutputIngredient() {
        this.outputIngredientId = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Reclassifies the product (Slice 4A). Both halves are mandatory in one call
     * so a product can never be left with a type/mode pair that was only partly
     * written. The allowed values are exactly the V039 enum CHECK sets; no new
     * value may be introduced here without a migration, and no category-specific
     * behaviour column may be added (behaviour comes from attached option groups).
     *
     * <p>Reclassification deliberately performs no availability or Recipe side
     * effect: a RECIPE_BASED product is not required to own a Recipe yet, and
     * changing the mode never deactivates the product. Those consequences are
     * computed by the availability/recipe engines from the current data.
     */
    public void applyClassification(ProductType type, StockMode mode) {
        if (type == null) {
            throw new IllegalArgumentException("Product type is required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Stock mode is required");
        }
        this.productType = type;
        this.stockMode = mode;
        this.updatedAt = Instant.now();
    }

    /**
     * Marks that a child of this aggregate (a customization-group relationship)
     * changed. The relationship itself lives in its own table with a composite
     * key and no version column, so the Product {@code @Version} is the lock
     * token the editor echoes; without dirtying this row Hibernate would issue
     * no UPDATE and the optimistic lock would silently never advance.
     */
    public void childCollectionChanged() {
        this.updatedAt = Instant.now();
    }

    public UUID id() {return id;}
    public String code() {return code;}
    public UUID categoryId() {return categoryId;}
    public String name() {return name;}
    public String description() {return description;}
    public String imageObjectKey() {return imageObjectKey;}
    public BigDecimal basePrice() {return basePrice;}
    public String currencyCode() {return currencyCode;}
    public int preparationMinutes() {return preparationMinutes;}
    public BigDecimal taxRate() {return taxRate;}
    public boolean customizable() {return customizable;}
    public boolean active() {return active;}
    public ProductType productType() {return productType;}
    public StockMode stockMode() {return stockMode;}
    public UUID activeRecipeVersionId() {return activeRecipeVersionId;}
    public UUID outputIngredientId() {return outputIngredientId;}
    public Instant createdAt() {return createdAt;}
    public Instant updatedAt() {return updatedAt;}
    public long version() {return version;}
}
