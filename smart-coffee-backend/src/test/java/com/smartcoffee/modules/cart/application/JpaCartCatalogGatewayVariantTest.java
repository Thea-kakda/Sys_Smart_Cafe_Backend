package com.smartcoffee.modules.cart.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.BusinessException;
import com.smartcoffee.modules.cart.application.CartCatalogGateway.VariantOffer;
import com.smartcoffee.modules.catalog.application.EffectiveCustomizationResolver;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.catalog.persistence.StoreProductRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 4B.1 unit tests — authoritative variant validation.
 *
 * <p>A supplied variant is accepted only when it exists (not soft-deleted),
 * belongs to the requested product, is active and carries a valid price. Every
 * rejection surfaces as a stable business error; there is NO silent fallback
 * to the product base price, because repricing a rejected variant silently
 * mischarges the customer.
 */
class JpaCartCatalogGatewayVariantTest {

    private static final UUID STORE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();

    private ProductVariantRepository variants;
    private JpaCartCatalogGateway gateway;

    @BeforeEach
    void setUp() {
        variants = mock(ProductVariantRepository.class);
        gateway = new JpaCartCatalogGateway(mock(ProductRepository.class), mock(StoreProductRepository.class),
                variants, mock(EffectiveCustomizationResolver.class));
    }

    private ProductVariant variant(String price, boolean active) {
        return ProductVariant.create(PRODUCT, "LARGE-16", "Large 16 oz", null, null, new BigDecimal(price), null,
                "USD", false, active, 0, null);
    }

    private void found(ProductVariant v) {
        when(variants.findByIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(v));
    }

    private String errorCode(Runnable call) {
        var e = assertThrows(BusinessException.class, call::run);
        return ((BusinessException) e).code();
    }

    @Test
    void nullVariantIsTheProductLevelLineAndResolvesToNothing() {
        assertNull(gateway.variant(STORE, PRODUCT, null));
    }

    @Test
    void validVariantResolvesWithItsAuthoritativePrice() {
        found(variant("4.5000", true));
        VariantOffer offer = gateway.variant(STORE, PRODUCT, VARIANT);
        assertNotNull(offer);
        assertEquals(0, new BigDecimal("4.5000").compareTo(offer.price()));
        assertEquals("Large 16 oz", offer.variantName());
        assertEquals("LARGE-16", offer.variantCode());
        assertEquals("USD", offer.currency());
    }

    @Test
    void missingOrSoftDeletedVariantIsRejected() {
        when(variants.findByIdAndDeletedAtIsNull(any())).thenReturn(Optional.empty());
        assertEquals("VARIANT_NOT_FOUND", errorCode(() -> gateway.variant(STORE, PRODUCT, VARIANT)));
    }

    @Test
    void variantOfAnotherProductIsRejected() {
        var foreign = ProductVariant.create(UUID.randomUUID(), "OTHER", "Other Product Size", null, null,
                new BigDecimal("4.5000"), null, "USD", false, true, 0, null);
        found(foreign);
        assertEquals("VARIANT_PRODUCT_MISMATCH", errorCode(() -> gateway.variant(STORE, PRODUCT, VARIANT)));
    }

    @Test
    void inactiveVariantIsRejected() {
        found(variant("4.5000", false));
        assertEquals("VARIANT_INACTIVE", errorCode(() -> gateway.variant(STORE, PRODUCT, VARIANT)));
    }

    @Test
    void invalidVariantPriceIsRejected() {
        found(variant("4.50000", true)); // scale 5 breaches the 4-decimal money convention
        assertEquals("INVALID_VARIANT_PRICE", errorCode(() -> gateway.variant(STORE, PRODUCT, VARIANT)));
    }
}
