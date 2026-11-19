package com.smartcoffee.modules.cart.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.cart.domain.*;
import com.smartcoffee.modules.cart.persistence.CartRepository;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import com.smartcoffee.modules.promotion.application.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the authoritative promotion quote context from the caller's own cart.
 *
 * <p>Customer identity comes from the authenticated actor's owned customer
 * profile, and store, currency, unit prices, option deltas, quantities, product
 * and category identity all come from the persisted cart snapshots. The public
 * request contributes only the store selector and the promotion code. Nothing
 * here writes a redemption or touches usage_count (Phase B1 §3, §6).
 */
@Service
public class CartPromotionQuoteService {
    private final CartRepository carts;
    private final StoreRepository stores;
    private final CurrentCustomerResolver customers;
    private final CartCatalogGateway catalog;
    private final PromotionQuoteService quotes;

    public CartPromotionQuoteService(CartRepository carts, StoreRepository stores, CurrentCustomerResolver customers,
            CartCatalogGateway catalog, PromotionQuoteService quotes) {
        this.carts = carts;
        this.stores = stores;
        this.customers = customers;
        this.catalog = catalog;
        this.quotes = quotes;
    }

    @Transactional(readOnly = true)
    public PromotionQuote quote(UUID userId, UUID storeId, String promotionCode) {
        UUID customerId = customers.customerId(userId);
        stores.findByIdAndActiveTrue(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "Store not found"));
        Cart cart = carts.findFirstByCustomerIdAndStoreIdAndStatusOrderByCreatedAtDesc(customerId, storeId,
                CartStatus.ACTIVE).orElseThrow(() -> new ResourceNotFoundException("CART_NOT_FOUND", "Cart not found"));
        cart.requireActive();

        Set<UUID> productIds = new LinkedHashSet<>();
        for (CartItem item : cart.items())
            productIds.add(item.productId());
        Map<UUID, UUID> categories = catalog.productCategoryIds(productIds);

        List<PromotionQuoteLine> lines = new ArrayList<>();
        for (CartItem item : cart.items()) {
            var authoritativeUnitPrice = item.unitBasePrice().add(item.unitCustomizationPrice());
            lines.add(new PromotionQuoteLine(item.id(), item.productId(), categories.get(item.productId()),
                    item.variantId(), item.quantity(), authoritativeUnitPrice, item.lineTotal()));
        }
        return quotes.quote(new PromotionQuoteContext(storeId, customerId, cart.currencyCode(), lines), promotionCode);
    }
}
