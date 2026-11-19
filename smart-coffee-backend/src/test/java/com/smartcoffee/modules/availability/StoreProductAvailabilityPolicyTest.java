package com.smartcoffee.modules.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.modules.availability.StoreProductAvailabilityPolicy.PolicyGate;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.StoreProduct;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StoreProductAvailabilityPolicy} — the ONLY place the engine
 * interprets the store/master listing controls (Slice 3A, spec §3/§12/§22 "Product
 * rules"). It proves ACTIVE / LISTED / MANUALLY_SOLD_OUT stay distinct concepts and
 * that the existing boolean model maps to {@link ManualAvailabilityState} without a
 * silent oversell override.
 */
class StoreProductAvailabilityPolicyTest {

    private final StoreProductAvailabilityPolicy policy = new StoreProductAvailabilityPolicy();

    private static Product product(boolean active) {
        Product p = mock(Product.class);
        when(p.active()).thenReturn(active);
        return p;
    }

    private static StoreProduct storeProduct(boolean effectiveAvailable) {
        StoreProduct sp = mock(StoreProduct.class);
        when(sp.available()).thenReturn(effectiveAvailable);
        return sp;
    }

    @Test
    void activeAndListedWithStockLeversClearIsOrderableAuto() {
        PolicyGate gate = policy.gate(product(true), storeProduct(true));
        assertTrue(gate.active());
        assertTrue(gate.listed());
        assertEquals(ManualAvailabilityState.AUTO, gate.manualState());
        assertTrue(gate.orderableIgnoringStock());
        assertTrue(gate.reasonCodes().isEmpty());
    }

    @Test
    void inactiveProductCarriesProductInactiveAndIsNotOrderable() {
        PolicyGate gate = policy.gate(product(false), storeProduct(true));
        assertFalse(gate.active());
        assertTrue(gate.reasonCodes().contains(AvailabilityReason.PRODUCT_INACTIVE.code()));
        assertFalse(gate.orderableIgnoringStock());
    }

    @Test
    void missingStoreProductIsNotListed() {
        PolicyGate gate = policy.gate(product(true), null);
        assertFalse(gate.listed());
        assertEquals(ManualAvailabilityState.AUTO, gate.manualState());
        assertTrue(gate.reasonCodes().contains(AvailabilityReason.PRODUCT_NOT_LISTED.code()));
        assertFalse(gate.orderableIgnoringStock());
    }

    @Test
    void effectiveUnavailableMapsToManualSoldOut() {
        PolicyGate gate = policy.gate(product(true), storeProduct(false));
        assertEquals(ManualAvailabilityState.MANUAL_SOLD_OUT, gate.manualState());
        assertTrue(gate.listed(), "the row exists, so the product is still listed/visible");
        assertTrue(gate.reasonCodes().contains(AvailabilityReason.PRODUCT_MANUALLY_SOLD_OUT.code()));
        assertFalse(gate.orderableIgnoringStock(), "manual sold-out blocks ordering regardless of stock");
    }

    @Test
    void reasonCodesAreOrderedAsProduced() {
        PolicyGate gate = policy.gate(product(false), storeProduct(false));
        List<String> codes = gate.reasonCodes();
        assertEquals(AvailabilityReason.PRODUCT_INACTIVE.code(), codes.get(0));
        assertEquals(AvailabilityReason.PRODUCT_MANUALLY_SOLD_OUT.code(), codes.get(1));
    }
}
