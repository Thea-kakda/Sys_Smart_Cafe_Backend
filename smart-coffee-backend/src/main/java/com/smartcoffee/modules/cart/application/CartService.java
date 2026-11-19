package com.smartcoffee.modules.cart.application;

import com.smartcoffee.common.exception.*;
import com.smartcoffee.modules.cart.api.*;
import com.smartcoffee.modules.cart.domain.*;
import com.smartcoffee.modules.cart.persistence.*;
import com.smartcoffee.modules.inventory.application.CartReservationService;
import com.smartcoffee.modules.inventory.application.ReservationDemand;
import com.smartcoffee.modules.inventory.application.ReservationReasons;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import java.math.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cart commands with inventory reservation (Slice 2C).
 *
 * <p>Every mutation that changes what a line consumes — add, quantity change,
 * reconfiguration, removal, clear — moves stock through
 * {@link CartReservationService} <b>inside the same transaction</b> as the cart
 * write. Reservation is calculated first: a line that cannot be reserved never
 * reaches the cart, and a failed mutation rolls back the reservation, the
 * balances and the ledger together.
 */
@Service
public class CartService {
    private static final Logger log = LoggerFactory.getLogger(CartService.class);
    private static final Duration CART_TTL = Duration.ofHours(24);
    private static final String SCOPE_ADD = "CART_ITEM_ADD";
    private static final String SCOPE_QUANTITY = "CART_ITEM_QUANTITY";
    private static final String SCOPE_CONFIGURE = "CART_ITEM_CONFIGURE";

    private final CartRepository carts;
    private final CartItemRepository items;
    private final CartCatalogGateway catalog;
    private final CartCustomizationValidator validator;
    private final CurrentCustomerResolver customers;
    private final StoreRepository stores;
    private final CartMapper mapper;
    private final CartDemandService demands;
    private final CartReservationService reservations;
    private final CartIdempotency idempotency;

    public CartService(CartRepository carts, CartItemRepository items, CartCatalogGateway catalog,
            CartCustomizationValidator validator, CurrentCustomerResolver customers, StoreRepository stores,
            CartMapper mapper, CartDemandService demands, CartReservationService reservations,
            CartIdempotency idempotency) {
        this.carts = carts;
        this.items = items;
        this.catalog = catalog;
        this.validator = validator;
        this.customers = customers;
        this.stores = stores;
        this.mapper = mapper;
        this.demands = demands;
        this.reservations = reservations;
        this.idempotency = idempotency;
    }

    @Transactional
    public CartResponse current(UUID userId, UUID storeId) {
        UUID customerId = customers.customerId(userId);
        var store = stores.findByIdAndActiveTrue(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
        Cart cart = carts
                .findFirstByCustomerIdAndStoreIdAndStatusOrderByCreatedAtDesc(customerId, storeId, CartStatus.ACTIVE)
                .orElseGet(() -> carts.save(Cart.customer(customerId, userId, storeId, store.defaultCurrency(),
                        Instant.now().plus(CART_TTL))));
        cart.requireActive();
        return mapper.response(cart);
    }

    /**
     * Adds a line and reserves its authoritative demand. The response is stored
     * for an {@code Idempotency-Key} retry before the transaction commits, so a
     * replay returns the original cart instead of reserving twice.
     */
    @Transactional
    public CartResponse add(UUID userId, UUID idempotencyKey, AddCartItemRequest request) {
        UUID customerId = customers.customerId(userId);
        var claim = idempotency.claim(SCOPE_ADD, userId, idempotencyKey, request);
        CartResponse replayed = idempotency.replay(claim, CartResponse.class);
        if (replayed != null) {
            log.info("idempotent replay scope={} userId={} cartId={}", SCOPE_ADD, userId, replayed.cartId());
            return replayed;
        }

        Cart cart = activeCart(customerId, userId, request.storeId());
        var offer = catalog.product(request.storeId(), request.productId());
        if (!offer.available())
            throw new ConflictException("PRODUCT_UNAVAILABLE", "Product is unavailable");
        if (!cart.currencyCode().equals(offer.currency()))
            throw new ConflictException("CURRENCY_MISMATCH", "Product currency does not match cart");
        var selections = validator.validate(catalog.groupRules(request.productId()), request.customizations());
        cart.extend(Instant.now().plus(CART_TTL));

        // Slice 4B.1: a selected variant is authoritative for pricing — its price
        // REPLACES the store/product base (option deltas add on top); an invalid
        // variant is rejected by the gateway, never silently fell back.
        var resolution = resolvePricing(request.storeId(), request.productId(), request.variantId(), cart, offer);
        CartItem item = CartItem.create(offer.productId(), request.variantId(), resolution, offer.productCode(),
                offer.productName(), request.quantity(), basePriceFor(offer, resolution), request.note(), selections);
        // Reserve before the line exists in the cart: a line that cannot be fully
        // reserved is rejected as a whole (never a partially reserved add).
        ReservationDemand demand = demands.demand(request.productId(), request.variantId(), request.quantity(),
                selections);
        reservations.apply(cart.storeId(), cart.id(), item.id(), demand, cart.expiresAt(), userId,
                ReservationReasons.CART_ITEM_ADDED, idempotencyKey == null ? null : idempotencyKey.toString());
        cart.add(item);

        CartResponse response = mapper.response(cart);
        idempotency.complete(claim, response);
        return response;
    }

    /** Quantity change reserves or releases only the delta. */
    @Transactional
    public CartResponse updateQuantity(UUID userId, UUID idempotencyKey, UUID cartId, UUID itemId, int quantity) {
        var claim = idempotency.claim(SCOPE_QUANTITY, userId, idempotencyKey,
                new QuantityChange(cartId, itemId, quantity));
        CartResponse replayed = idempotency.replay(claim, CartResponse.class);
        if (replayed != null) {
            return replayed;
        }

        Cart cart = ownedLock(userId, cartId);
        CartItem item = item(cart, itemId);
        int previous = item.quantity();
        if (previous != quantity) {
            cart.extend(Instant.now().plus(CART_TTL));
            ReservationDemand demand = demands.demand(item.productId(), item.variantId(), quantity,
                    item.customizations());
            String reason = quantity > previous
                    ? ReservationReasons.CART_QUANTITY_INCREASED
                    : ReservationReasons.CART_QUANTITY_DECREASED;
            reservations.apply(cart.storeId(), cart.id(), item.id(), demand, cart.expiresAt(), userId, reason,
                    idempotencyKey == null ? null : idempotencyKey.toString());
            item.quantity(quantity);
        }

        CartResponse response = mapper.response(cart);
        idempotency.complete(claim, response);
        return response;
    }

    /**
     * Variant and/or customization change. The old demand is read from the
     * persisted reservation snapshot, the new one from the authoritative plan, and
     * only the per-ingredient delta is applied.
     */
    @Transactional
    public CartResponse configure(UUID userId, UUID idempotencyKey, UUID cartId, UUID itemId,
            ConfigureCartItemRequest request) {
        var claim = idempotency.claim(SCOPE_CONFIGURE, userId, idempotencyKey,
                new ConfigurationChange(cartId, itemId, request.variantId(), request.customizations()));
        CartResponse replayed = idempotency.replay(claim, CartResponse.class);
        if (replayed != null) {
            return replayed;
        }

        Cart cart = ownedLock(userId, cartId);
        CartItem item = item(cart, itemId);
        var offer = catalog.product(cart.storeId(), item.productId());
        if (!offer.available())
            throw new ConflictException("PRODUCT_UNAVAILABLE", "Product is unavailable");
        if (!cart.currencyCode().equals(offer.currency()))
            throw new ConflictException("CURRENCY_MISMATCH", "Product currency does not match cart");
        var selections = validator.validate(catalog.groupRules(item.productId()), request.customizations());
        cart.extend(Instant.now().plus(CART_TTL));

        var resolution = resolvePricing(cart.storeId(), item.productId(), request.variantId(), cart, offer);

        ReservationDemand demand = demands.demand(item.productId(), request.variantId(), item.quantity(), selections);
        reservations.apply(cart.storeId(), cart.id(), item.id(), demand, cart.expiresAt(), userId,
                ReservationReasons.CART_CONFIGURATION_CHANGED,
                idempotencyKey == null ? null : idempotencyKey.toString());

        // Flush the orphan deletions before inserting the replacement rows: the
        // (cart_item_id, option_id) uniqueness would otherwise collide on an
        // option that survives the swap.
        item.clearCustomizations();
        items.saveAndFlush(item);
        item.applyConfiguration(request.variantId(), resolution, basePriceFor(offer, resolution), selections);

        CartResponse response = mapper.response(cart);
        idempotency.complete(claim, response);
        return response;
    }

    /** Removes a line and gives back everything it holds. Repeats are safe. */
    @Transactional
    public CartResponse remove(UUID userId, UUID cartId, UUID itemId) {
        Cart cart = ownedLock(userId, cartId);
        reservations.releaseItem(itemId, ReservationReasons.CART_ITEM_REMOVED, userId);
        cart.remove(itemId);
        return mapper.response(cart);
    }

    /** Clears the cart and gives back every active hold of it. Repeats are safe. */
    @Transactional
    public CartResponse clear(UUID userId, UUID cartId) {
        Cart cart = ownedLock(userId, cartId);
        reservations.releaseCart(cart.id(), ReservationReasons.CART_CLEARED, userId);
        cart.clear();
        return mapper.response(cart);
    }

    private Cart activeCart(UUID customerId, UUID userId, UUID storeId) {
        var store = stores.findByIdAndActiveTrue(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Store not found"));

        return carts
                .findFirstByCustomerIdAndStoreIdAndStatusOrderByCreatedAtDesc(
                        customerId,
                        storeId,
                        CartStatus.ACTIVE)
                // A brand-new cart is flushed eagerly: reservations carry a cart_id
                // foreign key, so the parent row must exist before the hold does.
                .orElseGet(() -> carts.saveAndFlush(
                        Cart.customer(
                                customerId,
                                userId,
                                storeId,
                                store.defaultCurrency(),
                                Instant.now()
                                        .plus(CART_TTL))));
    }

    private Cart ownedLock(UUID userId, UUID cartId) {
        UUID customerId = customers.customerId(userId);
        Cart c = carts.lockOwned(cartId, customerId).orElseThrow(() -> new ResourceNotFoundException("Cart not found"));
        c.requireActive();
        return c;
    }

    private CartItem item(Cart cart, UUID itemId) {
        return cart.items().stream().filter(x -> x.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("CART_ITEM_NOT_FOUND", "Cart item not found"));
    }

    /**
     * Resolves the authoritative variant for a cart write (Slice 4B.1). Null is
     * the product-level line. The variant must carry the cart's currency, like
     * the product offer before it; every other validity rule (exists, belongs,
     * active, price) is enforced by the gateway with a stable error.
     */
    private CartItem.VariantSnapshot resolvePricing(UUID storeId, UUID productId, UUID variantId, Cart cart,
            CartCatalogGateway.ProductOffer offer) {
        if (variantId == null)
            return null;
        var variant = catalog.variant(storeId, productId, variantId);
        if (!cart.currencyCode().equals(variant.currency()))
            throw new ConflictException("CURRENCY_MISMATCH", "Variant currency does not match cart");
        return new CartItem.VariantSnapshot(variant.variantCode(), variant.variantName(), variant.price());
    }

    /** Variant price replaces the store/product base; null snapshot keeps it. */
    private static BigDecimal basePriceFor(CartCatalogGateway.ProductOffer offer, CartItem.VariantSnapshot variant) {
        return variant == null ? offer.unitPrice() : variant.price();
    }

    /** Canonical idempotency payloads: same key + different request is a conflict. */
    private record QuantityChange(UUID cartId, UUID itemId, int quantity) {
    }

    private record ConfigurationChange(UUID cartId, UUID itemId, UUID variantId,
            List<SelectedCustomizationRequest> customizations) {
    }
}
