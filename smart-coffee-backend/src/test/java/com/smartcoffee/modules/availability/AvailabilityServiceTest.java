package com.smartcoffee.modules.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.StockMode;
import com.smartcoffee.modules.catalog.domain.StoreProduct;
import com.smartcoffee.modules.catalog.domain.StoreProductId;
import com.smartcoffee.modules.catalog.application.EffectiveCustomizationResolver;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.catalog.persistence.StoreProductRepository;
import com.smartcoffee.modules.inventory.persistence.ReconciliationFindingRepository;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Resolved;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Source;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Orchestration unit tests for {@link AvailabilityService} (Slice 3A, spec §15/§22
 * "Product rules"). The pure maths (serving formula, effect application, group
 * satisfiability) are covered by their own suites; this exercises the ENGINE wiring:
 * the ACTIVE/LISTED/MANUAL/STOCKMODE decision branches, the not-tracked and missing
 * recipe paths, and the batch/validate contracts — with the recipe/option/inventory
 * collaborators stubbed so the branch taken is what is under test.
 */
class AvailabilityServiceTest {

    private final UUID store = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    private final UUID product = UUID.fromString("f0000000-0000-0000-0000-0000000000a9");

    private ProductRepository products;
    private ProductVariantRepository variants;
    private StoreProductRepository storeProducts;
    private EffectiveCustomizationResolver customization;
    private RecipeVersionResolver resolver;
    private RecipeAvailabilityCalculator recipeCalculator;
    private InventoryAvailabilityReader inventoryReader;
    private OptionAvailabilityEvaluator optionEvaluator;
    private ReconciliationFindingRepository reconciliationFindings;
    private AvailabilityMetrics metrics;
    private Clock clock;

    private AvailabilityService service;

    @BeforeEach
    void setup() {
        products = mock(ProductRepository.class);
        variants = mock(ProductVariantRepository.class);
        storeProducts = mock(StoreProductRepository.class);
        customization = mock(EffectiveCustomizationResolver.class);
        resolver = mock(RecipeVersionResolver.class);
        recipeCalculator = mock(RecipeAvailabilityCalculator.class);
        inventoryReader = mock(InventoryAvailabilityReader.class);
        optionEvaluator = mock(OptionAvailabilityEvaluator.class);
        reconciliationFindings = mock(ReconciliationFindingRepository.class);
        metrics = mock(AvailabilityMetrics.class);
        clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);

        service = new AvailabilityService(products, variants, storeProducts, customization, resolver,
                recipeCalculator, inventoryReader, optionEvaluator, new StoreProductAvailabilityPolicy(),
                reconciliationFindings, metrics, clock, 5000);

        when(inventoryReader.read(any(), any())).thenReturn(Map.of());
        when(customization.resolve(any())).thenReturn(List.of());
        when(variants.findActiveByProduct(any())).thenReturn(List.of());
    }

    private Product product(boolean active, StockMode mode) {
        Product p = mock(Product.class);
        when(p.id()).thenReturn(product);
        when(p.active()).thenReturn(active);
        when(p.stockMode()).thenReturn(mode);
        when(p.version()).thenReturn(0L);
        return p;
    }

    private void listedStoreProduct(boolean effectiveAvailable) {
        StoreProduct sp = mock(StoreProduct.class);
        when(sp.available()).thenReturn(effectiveAvailable);
        when(storeProducts.findById(new StoreProductId(store, product))).thenReturn(Optional.of(sp));
    }

    @Test
    void inactiveProductIsNotOrderable() {
        Product p = product(false, StockMode.NOT_TRACKED);
        when(products.findById(product)).thenReturn(Optional.of(p));
        listedStoreProduct(true);
        when(resolver.resolve(product, null)).thenReturn(new Resolved(Source.NONE, null));

        ProductAvailabilityResult r = service.availabilityForProduct(store, product, 1);

        assertFalse(r.active());
        assertTrue(r.listed());
        assertFalse(r.orderable());
        assertTrue(r.reasonCodes().contains(AvailabilityReason.PRODUCT_INACTIVE.code()));
    }

    @Test
    void notTrackedProductIsOrderableWithoutInventoryAndHasNullServings() {
        Product p = product(true, StockMode.NOT_TRACKED);
        when(products.findById(product)).thenReturn(Optional.of(p));
        listedStoreProduct(true);
        when(resolver.resolve(product, null)).thenReturn(new Resolved(Source.NONE, null));

        ProductAvailabilityResult r = service.availabilityForProduct(store, product, 1);

        assertTrue(r.orderable());
        assertTrue(r.inStock());
        assertNull(r.availableBaseServings(), "not-inventory-tracked => servings not meaningfully computed");
    }

    @Test
    void recipeBasedWithoutResolvedRecipeIsBlocked() {
        Product p = product(true, StockMode.RECIPE_BASED);
        when(products.findById(product)).thenReturn(Optional.of(p));
        listedStoreProduct(true);
        when(resolver.resolve(product, null)).thenReturn(new Resolved(Source.NONE, null));

        ProductAvailabilityResult r = service.availabilityForProduct(store, product, 1);

        assertFalse(r.orderable());
        assertTrue(r.reasonCodes().contains(AvailabilityReason.ACTIVE_RECIPE_REQUIRED.code()));
    }

    @Test
    void manualSoldOutBlocksOrderingEvenWithStock() {
        Product p = product(true, StockMode.NOT_TRACKED);
        when(products.findById(product)).thenReturn(Optional.of(p));
        listedStoreProduct(false); // effective unavailable -> MANUAL_SOLD_OUT
        when(resolver.resolve(product, null)).thenReturn(new Resolved(Source.NONE, null));

        ProductAvailabilityResult r = service.availabilityForProduct(store, product, 1);

        assertEquals(ManualAvailabilityState.MANUAL_SOLD_OUT, r.manualState());
        assertFalse(r.orderable());
        assertTrue(r.reasonCodes().contains(AvailabilityReason.PRODUCT_MANUALLY_SOLD_OUT.code()));
    }

    @Test
    void batchRepresentsMissingProductWithoutThrowing() {
        UUID missing = UUID.fromString("f0000000-0000-0000-0000-0000000000ee");
        when(products.findById(missing)).thenReturn(Optional.empty());
        when(products.findAllById(any())).thenReturn(List.of());

        List<ProductAvailabilityResult> results = service.availabilityForProducts(store, List.of(missing), 1);

        assertEquals(1, results.size());
        assertFalse(results.get(0).orderable());
        assertEquals(AvailabilityReason.PRODUCT_NOT_FOUND.code(), results.get(0).reasonCodes().get(0));
    }

    @Test
    void unknownProductThrowsAtRequestLevel() {
        when(products.findById(product)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.availabilityForProduct(store, product, 1));
    }

    @Test
    void invalidQuantityIsRejectedBeforeAnyLookup() {
        assertThrows(ValidationException.class, () -> service.availabilityForProduct(store, product, 0));
        assertThrows(ValidationException.class, () -> service.availabilityForProduct(store, product, 10_001));
    }

    @Test
    void batchEmptyInputIsFastAndSideEffectFree() {
        List<ProductAvailabilityResult> results = service.availabilityForProducts(store, List.of(), 1);
        assertTrue(results.isEmpty());
        org.mockito.Mockito.verifyNoInteractions(products);
    }
}
