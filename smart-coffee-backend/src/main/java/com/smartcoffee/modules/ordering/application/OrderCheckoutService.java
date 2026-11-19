package com.smartcoffee.modules.ordering.application;

import tools.jackson.databind.ObjectMapper;
import com.smartcoffee.common.exception.*;
import com.smartcoffee.infrastructure.idempotency.*;
import com.smartcoffee.infrastructure.messaging.*;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.modules.cart.application.CartDemandService;
import com.smartcoffee.modules.cart.application.CurrentCustomerResolver;
import com.smartcoffee.modules.cart.domain.Cart;
import com.smartcoffee.modules.cart.domain.CartItem;

import com.smartcoffee.modules.cart.persistence.CartRepository;
import com.smartcoffee.modules.inventory.application.InventoryReservationFinalizationService;
import com.smartcoffee.modules.inventory.application.InventoryReservationFinalizationService.CartHold;
import com.smartcoffee.modules.inventory.application.InventoryReservationFinalizationService.LegacyLine;
import com.smartcoffee.modules.inventory.application.ReservationDemand;
import com.smartcoffee.modules.ordering.api.*;
import com.smartcoffee.modules.ordering.domain.FulfillmentType;
import com.smartcoffee.modules.ordering.domain.Order;
import com.smartcoffee.modules.ordering.domain.OrderItem;
import com.smartcoffee.modules.ordering.domain.OrderItemCustomization;
import com.smartcoffee.modules.ordering.domain.OrderPromotion;
import com.smartcoffee.modules.ordering.persistence.OrderRepository;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import com.smartcoffee.modules.promotion.application.PromotionCheckoutReservationService;
import com.smartcoffee.modules.promotion.application.PromotionCheckoutReservationService.PromotionGrant;
import com.smartcoffee.modules.promotion.application.PromotionDiscountAllocator;
import com.smartcoffee.modules.promotion.application.PromotionQuoteContext;
import com.smartcoffee.modules.promotion.application.PromotionQuoteLine;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout: prices the cart, creates the order and hands the cart's reservation
 * over to it (Slice 2D), now including the atomic Promotion reservation
 * (Phase B2).
 *
 * <p>The order reservation is built from the <b>persisted cart reservation
 * snapshot</b>, never from a fresh recipe lookup, so the variant recipe, the milk
 * replacement, the sugar multiplier, the extra-shot effect and the packaging
 * variant that the customer configured are exactly what the order holds and
 * later consumes. Ownership moves as metadata inside this one transaction: no
 * {@code SALE_RELEASE} and no second {@code SALE_RESERVATION} is written, so the
 * stock is never briefly unheld.
 *
 * <p>Promotion handling follows the same principle. A client submits only a
 * code; the promotion context (store, customer, currency, line prices, variant,
 * quantities, product/category targeting) is rebuilt here from the
 * authoritative cart and catalog. The discount is allocated to eligible lines,
 * tax is computed on the post-discount taxable amount, and usage is claimed
 * atomically on the promotion row inside this transaction. A prior quote is
 * advisory only and is never an input.
 */
@Service
public class OrderCheckoutService {
    /** Order hold lifetime: the payment window the order reservation lives in. */
    private static final Duration ORDER_RESERVATION_TTL = Duration.ofMinutes(15);

    private final CartRepository carts;
    private final OrderRepository orders;
    private final CheckoutCatalogPort catalog;
    private final CartDemandService plans;
    private final InventoryReservationFinalizationService finalization;
    private final CurrentCustomerResolver customers;
    private final StoreRepository stores;
    private final OrderNumberGenerator numbers;
    private final OrderMapper mapper;
    private final PromotionCheckoutReservationService promotions;
    private final IdempotencyService idem;
    private final CanonicalRequestHasher hasher;
    private final OutboxEventWriter outbox;
    private final ObjectMapper json;

    public OrderCheckoutService(CartRepository c, OrderRepository o, CheckoutCatalogPort cat, CartDemandService p,
            InventoryReservationFinalizationService f, CurrentCustomerResolver cu, StoreRepository s,
            OrderNumberGenerator n, OrderMapper m, PromotionCheckoutReservationService pr, IdempotencyService id,
            CanonicalRequestHasher h, OutboxEventWriter out, ObjectMapper j) {
        carts = c;
        orders = o;
        catalog = cat;
        plans = p;
        finalization = f;
        customers = cu;
        stores = s;
        numbers = n;
        mapper = m;
        promotions = pr;
        idem = id;
        hasher = h;
        outbox = out;
        json = j;
    }

    @Transactional
    public OrderResponse checkout(UUID userId, UUID idempotencyKey, CreateOrderRequest request) {
        UUID customer = customers.customerId(userId);
        String hash = hasher.hash(request);
        var claim = idem.claim("ORDER_CREATE", "USER", userId, idempotencyKey, hash, Duration.ofDays(7));
        if (claim.status() == IdempotencyStatus.COMPLETED) {
            try {
                return json.readValue(claim.responseBody(), OrderResponse.class);
            } catch (Exception e) {
                throw new InfrastructureException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot replay response");
            }
        }
        Cart cart = carts.lockOwned(request.cartId(), customer)
                .orElseThrow(() -> new ResourceNotFoundException("CART_NOT_FOUND", "Cart not found"));
        // Checked-out is reported before the generic state guard: a cart that
        // already produced an order must say so, not "not active".
        if (orders.existsByCartId(cart.id()))
            throw new ConflictException("CART_ALREADY_CHECKED_OUT", "Cart already checked out");
        cart.requireActive();
        if (cart.items().isEmpty())
            throw new ConflictException("EMPTY_CART", "Cart is empty");
        FulfillmentType fulfillment;
        try {
            fulfillment = FulfillmentType.valueOf(request.fulfillmentType());
        } catch (Exception e) {
            throw new ValidationException("Invalid fulfillment type");
        }
        var store = stores.findByIdAndActiveTrue(cart.storeId())
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));

        // The cart's holds are the authoritative demand: one per configured line,
        // already scaled by quantity and already carrying its variant recipe.
        Map<UUID, CartHold> holds = finalization.holdsForCart(cart.id());

        List<ValidatedLine> lines = new ArrayList<>();
        Set<UUID> productIds = new LinkedHashSet<>();
        for (CartItem ci : cart.items()) {
            var selected = ci.customizations().stream()
                    .map(x -> new CheckoutCatalogPort.SelectedOption(x.groupId(), x.optionId(), x.quantity())).toList();
            var valid = catalog.validate(cart.storeId(), ci.productId(), ci.variantId(), ci.quantity(), selected);
            if (!valid.currency().equals(cart.currencyCode()))
                throw new ConflictException("CURRENCY_MISMATCH", "Currency changed");
            if (valid.basePrice().compareTo(ci.unitBasePrice()) != 0
                    || valid.customizationPrice().compareTo(ci.unitCustomizationPrice()) != 0)
                throw new ConflictException("PRICE_CHANGED", "Catalog price changed");
            BigDecimal lineBeforeTax = valid.basePrice().add(valid.customizationPrice())
                    .multiply(BigDecimal.valueOf(ci.quantity()));
            var custom = valid.options().stream().map(x -> new OrderItemCustomization(x.groupId(), x.optionId(),
                    x.groupName(), x.optionName(), x.price(), x.quantity())).toList();
            lines.add(new ValidatedLine(ci, valid, lineBeforeTax, custom));
            productIds.add(ci.productId());
        }
        Map<UUID, UUID> categories = catalog.productCategoryIds(productIds);

        Instant holdUntil = Instant.now().plus(ORDER_RESERVATION_TTL);
        PromotionGrant grant = request.promotionCode() == null || request.promotionCode().isBlank() ? null
                : promotions.reserve(quoteContext(cart, customer, lines, categories), request.promotionCode());

        Map<UUID, BigDecimal> discounts = Map.of();
        if (grant != null) {
            Set<UUID> affected = new HashSet<>(grant.affectedCartItemIds());
            List<PromotionDiscountAllocator.Line> eligible = lines.stream()
                    .filter(line -> affected.contains(line.cart().id()))
                    .map(line -> new PromotionDiscountAllocator.Line(line.cart().id(), line.subtotal())).toList();
            discounts = PromotionDiscountAllocator.allocate(grant.discountAmount(), eligible);
        }
        OrderPricing.Result pricing = OrderPricing.price(lines.stream()
                .map(line -> new OrderPricing.Line(line.cart().id(), line.subtotal(), line.valid().taxRate())).toList(),
                discounts);

        List<OrderItem> items = new ArrayList<>();
        List<LegacyLine> legacyDemands = new ArrayList<>();
        Map<UUID, UUID> orderLineByCartItem = new LinkedHashMap<>();
        for (ValidatedLine line : lines) {
            CartItem ci = line.cart();
            CheckoutCatalogPort.ValidatedItem valid = line.valid();
            OrderPricing.PricedLine priced = pricing.lines().get(ci.id());
            CartHold hold = holds.get(ci.id());
            // Provenance: the recipe version that produced the held demand travels
            // with the order line, so the sold configuration stays reconstructible.
            UUID recipeVersion = hold == null ? null : hold.recipeVersionId();
            // Slice 4B.1: the line's variant identity travels as the CART's
            // snapshot (not the live catalog row), so a later variant rename or
            // price edit cannot rewrite what this order actually sold.
            OrderItem item = new OrderItem(valid.productId(), ci.variantId(), ci.id(), recipeVersion,
                    valid.stationCode(), valid.code(), valid.name(), ci.quantity(), valid.basePrice(),
                    valid.customizationPrice(), priced.taxAmount(), priced.lineTotal(), ci.note(), ci.variantCode(),
                    ci.variantName(), ci.variantPrice(), line.custom());
            item.applyLineDiscount(priced.discountAmount());
            items.add(item);
            orderLineByCartItem.put(ci.id(), item.id());
            if (hold == null) {
                // Legacy / never-held line: calculate the same authoritative plan the
                // cart would have calculated, and reserve exactly that for the order.
                ReservationDemand demand = plans.demand(ci.productId(), ci.variantId(), ci.quantity(),
                        ci.customizations());
                if (!demand.isEmpty()) {
                    legacyDemands.add(new LegacyLine(ci.id(), item.id(), demand));
                }
            }
        }

        OrderPromotion snapshot = grant == null ? null
                : new OrderPromotion(grant.promotionId(), grant.code(), grant.name(), grant.type(), grant.value(),
                        grant.eligibleSubtotal(), grant.discountAmount(), grant.promotionVersion());
        Order order = orders.save(Order.create(numbers.next(store.code()), cart.storeId(), customer, cart.id(),
                fulfillment, cart.currencyCode(), pricing.subtotal(), pricing.taxAmount(), pricing.totalAmount(),
                items, snapshot));
        if (grant != null) {
            // The promotion usage increment (held under the promotion row lock)
            // and this RESERVED redemption commit only together with the order.
            promotions.record(grant, order.id(), customer, cart.storeId(), holdUntil, userId, idempotencyKey);
        }

        finalization.convert(cart.storeId(), cart.id(), order.id(), holdUntil, orderLineByCartItem, legacyDemands,
                userId, idempotencyKey == null ? null : idempotencyKey.toString());

        cart.checkout();
        var payload = json.createObjectNode().put("orderId", order.id().toString()).put("orderNumber", order.number())
                .put("storeId", order.storeId().toString()).put("customerId", customer.toString())
                .put("status", order.status().name()).put("totalAmount", order.total().toPlainString())
                .put("currencyCode", order.currency());
        if (grant != null)
            payload.put("promotionId", grant.promotionId().toString()).put("promotionCode", grant.code())
                    .put("discountAmount", grant.discountAmount().toPlainString());
        outbox.append(new EventEnvelope(UUID.randomUUID(), EventTypes.ORDER_PLACED, 1, "ORDER", order.id(),
                order.storeId(), Instant.now(), null, null, payload), "order.placed.v1");
        OrderResponse response = mapper.response(order, false);
        try {
            claim.complete(201, json.writeValueAsString(response));
        } catch (Exception e) {
            throw new InfrastructureException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot store response");
        }
        return response;
    }

    private static PromotionQuoteContext quoteContext(Cart cart, UUID customer, List<ValidatedLine> lines,
            Map<UUID, UUID> categories) {
        List<PromotionQuoteLine> quoteLines = lines.stream()
                .map(line -> new PromotionQuoteLine(line.cart().id(), line.valid().productId(),
                        categories.get(line.valid().productId()), line.cart().variantId(), line.cart().quantity(),
                        line.valid().basePrice().add(line.valid().customizationPrice()), line.subtotal()))
                .toList();
        return new PromotionQuoteContext(cart.storeId(), customer, cart.currencyCode(), quoteLines);
    }

    private record ValidatedLine(CartItem cart, CheckoutCatalogPort.ValidatedItem valid, BigDecimal subtotal,
            List<OrderItemCustomization> custom) {
    }
}
