package com.smartcoffee.modules.catalog.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A sellable form of a {@link Product} with its own SKU, price and recipe.
 * Sizes that change price, packaging or consumption (Iced Latte Regular vs
 * Large, Cake slice vs 6-inch vs 8-inch) MUST be modelled as variants —
 * never as separate products, and never as option values.
 *
 * A product may legitimately have zero variants (bottled water, service
 * charge). When it does, the base {@code Product.basePrice} remains
 * authoritative; existing catalog / POS / Customer endpoints keep working
 * unchanged. When variants ARE present, an application-layer rule enforces
 * exactly one {@code is_default=true AND active=true} variant per product,
 * mirrored by the partial unique index
 * {@code uk_variant_one_default_per_product} from migration V039.
 *
 * Soft delete is used so historical order references stay readable.
 */
@Entity
@Table(
    schema = "catalog",
    name = "product_variants",
    uniqueConstraints = @UniqueConstraint(name = "uk_variant_code_per_product", columnNames = {"product_id", "variant_code"})
)
public class ProductVariant {
    @Id @Column(name = "variant_id") private UUID id;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "variant_code", nullable = false, length = 80) private String code;
    @Column(name = "variant_name", nullable = false, length = 150) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(length = 64) private String barcode;
    @Column(nullable = false, precision = 14, scale = 4) private BigDecimal price;
    @Column(precision = 14, scale = 4) private BigDecimal cost;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;
    @Column(name = "is_default", nullable = false) private boolean defaultVariant;
    @Column(nullable = false) private boolean active;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Column(name = "image_object_key") private String imageObjectKey;
    // Slice 2A: optional explicit recipe-version override (FK to recipe.recipe_versions).
    // Null means the variant inherits the product's ACTIVE recipe. It never auto-syncs.
    @Column(name = "active_recipe_version_id") private UUID activeRecipeVersionId;
    // Slice 5A: variant override of the product-level finished-good output
    // mapping (V051). When set, production planning uses this ingredient
    // instead of the product pointer; when null the product fallback applies.
    @Column(name = "output_ingredient_id") private UUID outputIngredientId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "deleted_at") private Instant deletedAt;
    @Version @Column(nullable = false) private long version;

    protected ProductVariant() {}

    /**
     * Domain factory. Referential / range checks that need repository access
     * (product exists, currency matches, price non-negative within scale,
     * no other active default for this product) are enforced by the
     * application service.
     */
    public static ProductVariant create(UUID productId, String code, String name, String description,
            String barcode, BigDecimal price, BigDecimal cost, String currencyCode,
            boolean defaultVariant, boolean active, int displayOrder, String imageObjectKey) {
        if (productId == null) throw new IllegalArgumentException("Variant requires a product");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Variant code (SKU) is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Variant name is required");
        if (price == null || price.signum() < 0) throw new IllegalArgumentException("Variant price must be non-negative");
        if (cost != null && cost.signum() < 0) throw new IllegalArgumentException("Variant cost must be non-negative");
        if (currencyCode == null || currencyCode.isBlank()) throw new IllegalArgumentException("Variant currency is required");
        var v = new ProductVariant();
        v.id = UUID.randomUUID();
        v.productId = productId;
        v.code = code.trim();
        v.name = name.trim();
        v.description = description;
        v.barcode = (barcode == null || barcode.isBlank()) ? null : barcode.trim();
        v.price = price;
        v.cost = cost;
        v.currencyCode = currencyCode.trim().toUpperCase();
        v.defaultVariant = defaultVariant;
        v.active = active;
        v.displayOrder = displayOrder;
        v.imageObjectKey = (imageObjectKey == null || imageObjectKey.isBlank()) ? null : imageObjectKey.trim();
        var now = Instant.now();
        v.createdAt = now;
        v.updatedAt = now;
        return v;
    }

    public void updateDetails(String code, String name, String description, String barcode,
            BigDecimal price, BigDecimal cost, String currencyCode, int displayOrder, String imageObjectKey) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Variant code (SKU) is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Variant name is required");
        if (price == null || price.signum() < 0) throw new IllegalArgumentException("Variant price must be non-negative");
        if (cost != null && cost.signum() < 0) throw new IllegalArgumentException("Variant cost must be non-negative");
        if (currencyCode == null || currencyCode.isBlank()) throw new IllegalArgumentException("Variant currency is required");
        this.code = code.trim();
        this.name = name.trim();
        this.description = description;
        this.barcode = (barcode == null || barcode.isBlank()) ? null : barcode.trim();
        this.price = price;
        this.cost = cost;
        this.currencyCode = currencyCode.trim().toUpperCase();
        this.displayOrder = displayOrder;
        this.imageObjectKey = (imageObjectKey == null || imageObjectKey.isBlank()) ? null : imageObjectKey.trim();
        this.updatedAt = Instant.now();
    }

    public void markDefault(boolean value) { this.defaultVariant = value; this.updatedAt = Instant.now(); }
    public void activate(boolean value)    { this.active = value;        this.updatedAt = Instant.now(); }
    public void softDelete()              { this.deletedAt = Instant.now(); this.updatedAt = this.deletedAt; }

    /** Pins an explicit ACTIVE recipe-version override (same product, validated by the service). */
    public void assignRecipeVersion(UUID recipeVersionId) {
        this.activeRecipeVersionId = recipeVersionId;
        this.updatedAt = Instant.now();
    }

    /** Clears the override so the variant falls back to the product's ACTIVE recipe. */
    public void clearRecipeVersion() {
        this.activeRecipeVersionId = null;
        this.updatedAt = Instant.now();
    }

    /** Slice 5A: pins a variant-specific finished-good output ingredient (validated by the service). */
    public void assignOutputIngredient(UUID outputIngredientId) {
        this.outputIngredientId = outputIngredientId;
        this.updatedAt = Instant.now();
    }

    /** Slice 5A: clears the variant override so the product-level mapping applies. */
    public void clearOutputIngredient() {
        this.outputIngredientId = null;
        this.updatedAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID productId() { return productId; }
    public String code() { return code; }
    public String name() { return name; }
    public String description() { return description; }
    public String barcode() { return barcode; }
    public BigDecimal price() { return price; }
    public BigDecimal cost() { return cost; }
    public String currencyCode() { return currencyCode; }
    public boolean isDefault() { return defaultVariant; }
    public boolean active() { return active; }
    public int displayOrder() { return displayOrder; }
    public String imageObjectKey() { return imageObjectKey; }
    public UUID activeRecipeVersionId() { return activeRecipeVersionId; }
    public UUID outputIngredientId() { return outputIngredientId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant deletedAt() { return deletedAt; }
    public long version() { return version; }
}
