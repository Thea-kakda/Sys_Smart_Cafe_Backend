package com.smartcoffee.modules.ordering.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(schema = "ordering", name = "order_items")
public class OrderItem {

    @Id
    @Column(name = "order_item_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id")
    private UUID productId;

    /**
     * Sellable variant this order line was checked out with (V046). Snapshotted
     * from the cart line so the demand that was reserved stays reconstructible
     * even after the variant is retired.
     */
    @Column(name = "variant_id")
    private UUID variantId;

    /**
     * Variant identity carried from the cart line's snapshot at checkout
     * (Slice 4B.1, V050). The sold label and price must stay readable after any
     * later variant rename or price edit, so these never resolve through a live
     * catalog join. Null for product-level lines.
     */
    @Column(name = "variant_code_snapshot", length = 80)
    private String variantCode;

    @Column(name = "variant_name_snapshot", length = 150)
    private String variantName;

    @Column(name = "variant_price_snapshot", precision = 14, scale = 4)
    private BigDecimal variantPrice;

    /**
     * The cart line this order line came from (V046). Deliberately not a foreign
     * key: cart lines are hard-deleted and an order snapshot must survive that.
     * It is the trace pointer back to the converted reservation.
     */
    @Column(name = "cart_item_id")
    private UUID cartItemId;

    @Column(name = "recipe_version_id")
    private UUID recipeVersionId;

    @Column(name = "station_code")
    private String stationCode;

    @Column(name = "product_code_snapshot", nullable = false)
    private String productCode;

    @Column(name = "product_name_snapshot", nullable = false)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(
            name = "unit_base_price",
            nullable = false,
            precision = 14,
            scale = 4
    )
    private BigDecimal unitBasePrice;

    @Column(
            name = "unit_customization_price",
            nullable = false,
            precision = 14,
            scale = 4
    )
    private BigDecimal unitCustomizationPrice;

    @Column(
            name = "line_discount_amount",
            nullable = false,
            precision = 14,
            scale = 4
    )
    private BigDecimal lineDiscount;

    @Column(
            name = "line_tax_amount",
            nullable = false,
            precision = 14,
            scale = 4
    )
    private BigDecimal lineTax;

    @Column(
            name = "line_total",
            nullable = false,
            precision = 14,
            scale = 4
    )
    private BigDecimal lineTotal;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(
            mappedBy = "orderItem",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    // Set (not List) so an Order can eager-fetch this association alongside the
    // ordered items bag in one entity graph; two simultaneous bag fetches throw
    // Hibernate's MultipleBagFetchException. Insertion order is preserved.
    private Set<OrderItemCustomization> customizations =
            new LinkedHashSet<>();

    protected OrderItem() {
    }

    public OrderItem(
            UUID product,
            UUID recipe,
            String station,
            String code,
            String name,
            int qty,
            BigDecimal base,
            BigDecimal custom,
            BigDecimal tax,
            BigDecimal total,
            String note,
            List<OrderItemCustomization> values) {
        this(product, null, null, recipe, station, code, name, qty, base, custom, tax, total, note, null, null, null,
                values);
    }

    /**
     * Checkout constructor (Slice 2D): the line additionally snapshots the cart
     * line and variant it came from, so the order keeps the exact provenance of
     * the demand that its reservation owns.
     */
    public OrderItem(
            UUID product,
            UUID variant,
            UUID cartItem,
            UUID recipe,
            String station,
            String code,
            String name,
            int qty,
            BigDecimal base,
            BigDecimal custom,
            BigDecimal tax,
            BigDecimal total,
            String note,
            List<OrderItemCustomization> values) {
        this(product, variant, cartItem, recipe, station, code, name, qty, base, custom, tax, total, note, null, null,
                null, values);
    }

    /**
     * Checkout constructor with variant identity (Slice 4B.1): the cart line's
     * variant code/name/price snapshot travels with the line, so historical
     * orders never depend on later catalog edits.
     */
    public OrderItem(
            UUID product,
            UUID variant,
            UUID cartItem,
            UUID recipe,
            String station,
            String code,
            String name,
            int qty,
            BigDecimal base,
            BigDecimal custom,
            BigDecimal tax,
            BigDecimal total,
            String note,
            String variantCode,
            String variantName,
            BigDecimal variantPrice,
            List<OrderItemCustomization> values) {

        id = UUID.randomUUID();
        productId = product;
        variantId = variant;
        cartItemId = cartItem;
        recipeVersionId = recipe;
        stationCode = station;
        productCode = code;
        productName = name;
        quantity = qty;
        unitBasePrice = base;
        unitCustomizationPrice = custom;
        lineDiscount = BigDecimal.ZERO;
        lineTax = tax;
        lineTotal = total;
        this.note = note;
        this.variantCode = variantCode;
        this.variantName = variantName;
        this.variantPrice = variantPrice;
        createdAt = Instant.now();

        values.forEach(value -> {
            value.attach(this);
            customizations.add(value);
        });
    }

    void attach(Order order) {
        this.order = order;
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

    public UUID cartItemId() {
        return cartItemId;
    }

    public UUID recipeVersionId() {
        return recipeVersionId;
    }

    public String stationCode() {
        return stationCode;
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

    public BigDecimal unitCustomizationPrice() {
        return unitCustomizationPrice;
    }

    public BigDecimal lineDiscount() {
        return lineDiscount;
    }

    /** Promotion discount allocated to this line by the checkout allocator. */
    public void applyLineDiscount(BigDecimal discount) {
        BigDecimal value = discount == null ? BigDecimal.ZERO : discount;
        if (value.signum() < 0 || value.compareTo(lineSubtotal()) > 0)
            throw new IllegalArgumentException("Line discount must be within the line subtotal");
        lineDiscount = value;
    }

    /** Subtotal before promotion, derived from the frozen unit prices. */
    public BigDecimal lineSubtotal() {
        return unitBasePrice.add(unitCustomizationPrice).multiply(BigDecimal.valueOf(quantity))
                .setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** Taxable amount after the allocated promotion discount. */
    public BigDecimal lineTaxableAmount() {
        return lineSubtotal().subtract(lineDiscount).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    public BigDecimal lineTax() {
        return lineTax;
    }

    public BigDecimal lineTotal() {
        return lineTotal;
    }

    public String note() {
        return note;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<OrderItemCustomization> customizations() {
        return List.copyOf(customizations);
    }
}
