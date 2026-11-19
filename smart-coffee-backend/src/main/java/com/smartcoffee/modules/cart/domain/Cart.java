package com.smartcoffee.modules.cart.domain;

import com.smartcoffee.common.exception.ConflictException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Entity
@Table(schema = "cart", name = "carts")
public class Cart {
    @Id
    @Column(name = "cart_id")
    private UUID id;
    @Column(name = "customer_id")
    private UUID customerId;
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CartSource source;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CartStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_type", length = 30)
    private FulfillmentType fulfillmentType;
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(nullable = false)
    private long version;
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public static Cart customer(UUID customerId, UUID userId, UUID storeId, String currency, Instant expiresAt) {
        Cart c = new Cart();
        c.id = UUID.randomUUID();
        c.customerId = customerId;
        c.createdByUserId = userId;
        c.storeId = storeId;
        c.source = CartSource.CUSTOMER_WEB;
        c.status = CartStatus.ACTIVE;
        c.currencyCode = currency;
        c.expiresAt = expiresAt;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return c;
    }

    public void requireActive() {
        if (status != CartStatus.ACTIVE)
            throw new ConflictException("CART_NOT_ACTIVE", "Cart is not active");
        if (!expiresAt.isAfter(Instant.now())) {
            status = CartStatus.EXPIRED;
            throw new ConflictException("CART_EXPIRED", "Cart has expired");
        }
    }

    public void add(CartItem item) {
        requireActive();
        item.attach(this);
        items.add(item);
        updatedAt = Instant.now();
    }

    public void remove(UUID itemId) {
        requireActive();
        if (!items.removeIf(i -> i.id().equals(itemId)))
            throw new ConflictException("CART_ITEM_NOT_FOUND", "Cart item not found");
        updatedAt = Instant.now();
    }

    public void clear() {
        requireActive();
        items.clear();
        updatedAt = Instant.now();
    }

    public void fulfillment(FulfillmentType type) {
        requireActive();
        fulfillmentType = type;
        updatedAt = Instant.now();
    }

    public void extend(Instant expiry) {
        requireActive();
        expiresAt = expiry;
        updatedAt = Instant.now();
    }

    /**
     * Deadline transition for the expiry worker (Slice 2C). Idempotent: only an
     * ACTIVE, elapsed cart transitions, so two workers racing on the same cart
     * can never release its reservations twice.
     *
     * @return true when this call moved the cart to EXPIRED
     */
    public boolean expireIfElapsed(Instant now) {
        if (status != CartStatus.ACTIVE || expiresAt.isAfter(now)) {
            return false;
        }
        status = CartStatus.EXPIRED;
        updatedAt = Instant.now();
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public UUID storeId() {
        return storeId;
    }

    public CartStatus status() {
        return status;
    }

    public String currencyCode() {
        return currencyCode;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public FulfillmentType fulfillmentType() {
        return fulfillmentType;
    }

    public List<CartItem> items() {
        // The cart entity graph join-fetches both this items bag (a List) and each
        // item's customizations collection. Hibernate emits one row per
        // (item x customization) and only de-duplicates Set-mapped collections, so a
        // line carrying N customizations would otherwise appear N times in this List.
        // Normalise to one entry per cart line (by id, creation order preserved) before
        // exposing the view. This is read-only: we never mutate the persisted bag, so
        // orphanRemoval is not triggered and no rows are deleted. Distinct lines are
        // preserved - only exact duplicate references of the SAME line collapse.
        return Collections.unmodifiableList(distinctItems());
    }

    private List<CartItem> distinctItems() {
        Map<UUID, CartItem> byId = new LinkedHashMap<>();
        for (CartItem item : items) {
            byId.putIfAbsent(item.id(), item);
        }
        return new ArrayList<>(byId.values());
    }

    public long version() {
        return version;
    }

    public BigDecimal subtotal() {
        return distinctItems().stream()
                .map(item -> item.lineTotal())
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b));
    }

    // adddddddd
    public void checkout() {
        requireActive();

        if (items.isEmpty()) {
            throw new ConflictException(
                    "CART_EMPTY",
                    "Cannot checkout an empty cart");
        }

        status = CartStatus.CHECKED_OUT;
        updatedAt = Instant.now();
    }
    // adddddddd
}

