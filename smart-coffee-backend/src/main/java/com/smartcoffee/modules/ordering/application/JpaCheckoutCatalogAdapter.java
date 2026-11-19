package com.smartcoffee.modules.ordering.application;

import com.smartcoffee.common.exception.*;
import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import com.smartcoffee.modules.cart.application.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class JpaCheckoutCatalogAdapter implements CheckoutCatalogPort {
    private final CartCatalogGateway catalog;
    private final CartCustomizationValidator validator;

    public JpaCheckoutCatalogAdapter(CartCatalogGateway c, CartCustomizationValidator v) {
        catalog = c;
        validator = v;
    }

    public ValidatedItem validate(UUID store, UUID product, int qty, List<SelectedOption> opts) {
        return validate(store, product, null, qty, opts);
    }

    public Map<UUID, UUID> productCategoryIds(Collection<UUID> productIds) {
        return catalog.productCategoryIds(productIds);
    }

    public ValidatedItem validate(UUID store, UUID product, UUID variant, int qty, List<SelectedOption> opts) {        var offer = catalog.product(store, product);
        if (!offer.available())
            throw new ConflictException("PRODUCT_UNAVAILABLE", "Product unavailable");
        // Slice 4B.1: the same authoritative resolution the cart used. A
        // supplied variant must still be valid and priced in the offer's
        // currency, and its price REPLACES the store/product base.
        var variantOffer = catalog.variant(store, product, variant);
        if (variantOffer != null && !variantOffer.currency().equals(offer.currency()))
            throw new ConflictException("CURRENCY_MISMATCH", "Variant currency changed");
        var basePrice = variantOffer == null ? offer.unitPrice() : variantOffer.price();
        var request = opts.stream().map(x -> new SelectedCustomizationRequest(x.groupId(), x.optionId(), x.quantity()))
                .toList();
        var values = validator.validate(catalog.groupRules(product), request);
        var mapped = values.stream().map(x -> new ValidatedOption(x.groupId(), x.optionId(), x.groupName(),
                x.optionName(), x.additionalPrice().divide(BigDecimal.valueOf(x.quantity())), x.quantity())).toList();
        var custom = values.stream().map(x -> x.additionalPrice())
            .reduce(BigDecimal.ZERO, (total, price) -> total.add(Objects.requireNonNull(price)));
        // Checkpoint 3C: station and tax are authoritative catalog values loaded
        // through the gateway (store_products.station_code and
        // catalog.products.tax_rate). The previous hard-coded "UNASSIGNED" and
        // BigDecimal.ZERO silently mispriced every order and disabled
        // production routing. A null station is passed through as-is: routing
        // handles it explicitly instead of the adapter inventing a station.
        return new ValidatedItem(product, offer.productCode(), offer.productName(), offer.stationCode(),
                basePrice, custom, offer.taxRate(), offer.currency(),
                variantOffer == null ? null : variantOffer.variantId(),
                variantOffer == null ? null : variantOffer.variantCode(),
                variantOffer == null ? null : variantOffer.variantName(), mapped);
    }
}

