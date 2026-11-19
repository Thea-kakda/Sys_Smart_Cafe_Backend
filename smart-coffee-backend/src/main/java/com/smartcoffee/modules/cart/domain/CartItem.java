package com.smartcoffee.modules.cart.domain;

import com.smartcoffee.common.exception.ValidationException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(schema = "cart", name = "cart_items")
public class CartItem {
    @Id
    @Column(name = "cart_item_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    /**
     * Sellable variant this line was configured against (V045). Null means the
     * product-level configuration, which resolves the product fallback recipe.
     */
    @Column(name = "variant_id")
    private UUID variantId;
    /**
     * Variant identity snapshotted at add/configure time (Slice 4B.1, V050).
     * The line must display and convert with the code/name/price the customer
     * actually saw, so these never resolve through a live catalog join.
     */
    @Column(name = "variant_code_snapshot", length = 80)
    private String variantCode;
    @Column(name = "variant_name_snapshot", length = 150)
    private String variantName;
    @Column(name = "variant_price_snapshot", precision = 14, scale = 4)
    private BigDecimal variantPrice;
    @Column(name = "product_code_snapshot", nullable = false, length = 50)
    private String productCode;
    @Column(name = "product_name_snapshot", nullable = false, length = 150)
    private String productName;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "unit_base_price_snapshot", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitBasePrice;
    @Column(length = 500)
    private String note;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(nullable = false)
    private long version;
    @OneToMany(mappedBy = "cartItem", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("groupName ASC, optionName ASC")
    // Set (not List) so a Cart can eager-fetch this association alongside the
    // ordered items bag in one entity graph; two simultaneous bag fetches throw
    // Hibernate's MultipleBagFetchException. @OrderBy keeps load order and the
    // getter exposes it as a List so callers are unaffected.
    private Set<CartItemCustomization> customizations = new LinkedHashSet<>();

    protected CartItem() {
    }

    public static CartItem create(UUID productId, String code, String name, int quantity, BigDecimal price, String note,
            List<CartItemCustomization> values) {
        return create(productId, null, null, code, name, quantity, price, note, values);
    }

    public static CartItem create(UUID productId, UUID variantId, VariantSnapshot variant, String code, String name,
            int quantity, BigDecimal price, String note, List<CartItemCustomization> values) {
        if (quantity <= 0)
            throw new ValidationException("Quantity must be positive");
        CartItem i = new CartItem();
        i.id = UUID.randomUUID();
        i.productId = productId;
        i.applyVariant(variantId, variant);
        i.productCode = code;
        i.productName = name;
        i.quantity = quantity;
        i.unitBasePrice = price;
        i.note = note;
        i.createdAt = Instant.now();
        i.updatedAt = i.createdAt;
        values.forEach(i::addCustomization);
        return i;
    }

    /** Variant identity captured for a cart line (Slice 4B.1). */
    public record VariantSnapshot(String code, String name, BigDecimal price) {
    }

    void attach(Cart cart) {
        this.cart = cart;
    }

    private void addCustomization(CartItemCustomization c) {
        c.attach(this);
        customizations.add(c);
    }

    public void quantity(int value) {
        if (value <= 0)
            throw new ValidationException("Quantity must be positive");
        quantity = value;
        updatedAt = Instant.now();
    }

    /**
     * Drops the current selection so the persistence context can flush the
     * deletes before the replacement rows are inserted. (cart_item_customizations
     * is unique per cart item + option, so an in-place swap must delete first.)
     */
    public void clearCustomizations() {
        customizations.clear();
        updatedAt = Instant.now();
    }

    /** Applies a new variant/customization configuration (Slice 2C reconfiguration). */
    public void applyConfiguration(UUID variantId, VariantSnapshot variant, BigDecimal basePrice,
            List<CartItemCustomization> values) {
        applyVariant(variantId, variant);
        if (basePrice != null) {
            this.unitBasePrice = basePrice;
        }
        values.forEach(this::addCustomization);
        updatedAt = Instant.now();
    }

    /**
     * Variant identity is always written together: clearing the variant (a
     * variant→none reconfiguration) must also clear its snapshot, so a
     * product-level line can never carry a stale variant label.
     */
    private void applyVariant(UUID variantId, VariantSnapshot variant) {
        this.variantId = variantId;
        if (variantId == null || variant == null) {
            this.variantCode = null;
            this.variantName = null;
            this.variantPrice = null;
        } else {
            this.variantCode = variant.code();
            this.variantName = variant.name();
            this.variantPrice = variant.price();
        }
    }

    public UUID id() {
        return id;
    }

    public UUID productId() {
        return productId;
    }

    public UUID variantId() {
        return variantId;
    }

    public String variantCode() {
        return variantCode;
    }

    public String variantName() {
        return variantName;
    }

    public BigDecimal variantPrice() {
        return variantPrice;
    }

    public String productCode() {
        return productCode;
    }

    public String productName() {
        return productName;
    }

    public int quantity() {
        return quantity;
    }

    public BigDecimal unitBasePrice() {
        return unitBasePrice;
    }

    public String note() {
        return note;
    }

    public List<CartItemCustomization> customizations() {
        return List.copyOf(customizations);
    }

    public BigDecimal unitCustomizationPrice() {
        return customizations.stream()
                .map(c -> c.additionalPrice())
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b));
    }

    public BigDecimal lineTotal() {
        return unitBasePrice.add(unitCustomizationPrice()).multiply(BigDecimal.valueOf(quantity));
    }
}
