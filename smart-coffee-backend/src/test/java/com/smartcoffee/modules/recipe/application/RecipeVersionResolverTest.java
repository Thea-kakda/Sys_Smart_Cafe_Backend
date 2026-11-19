package com.smartcoffee.modules.recipe.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Resolved;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Source;
import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.persistence.RecipeVersionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Precedence tests for the shared {@link RecipeVersionResolver} under the
 * Slice 2B.1 ASSIGNMENT model: resolution follows the explicit variant
 * override pointer, then the product fallback pointer — never a "status =
 * ACTIVE" query. Pointers to missing / non-ACTIVE / foreign-product versions
 * degrade to the next tier instead of mis-selling. Also asserts the
 * product-only path never consults the variant repository and that the
 * resolver performs deterministic single-query reads (findWithLinesById only).
 */
class RecipeVersionResolverTest {

    private static final UUID PRODUCT = UUID.fromString("30000000-0000-0000-0000-0000000000bb");
    private static final UUID OTHER_PRODUCT = UUID.fromString("30000000-0000-0000-0000-0000000000cc");
    private static final UUID VARIANT = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID OVERRIDE_VERSION = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID FALLBACK_VERSION = UUID.fromString("90000000-0000-0000-0000-000000000002");

    private RecipeVersionRepository recipes;
    private ProductVariantRepository variants;
    private ProductRepository products;
    private RecipeVersionResolver resolver;

    @BeforeEach
    void setup() {
        recipes = mock(RecipeVersionRepository.class);
        variants = mock(ProductVariantRepository.class);
        products = mock(ProductRepository.class);
        resolver = new RecipeVersionResolver(recipes, variants, products);
    }

    // Helpers build their mocks BEFORE any outer when(...) is opened: stubbing a
    // fresh mock inside an unfinished when() chain is an UnfinishedStubbing error.

    private RecipeVersion versionOf(RecipeStatus status, UUID productId) {
        RecipeVersion v = mock(RecipeVersion.class);
        when(v.status()).thenReturn(status);
        when(v.productId()).thenReturn(productId);
        return v;
    }

    private ProductVariant variantOf(UUID productId, UUID assignedVersionId) {
        ProductVariant v = mock(ProductVariant.class);
        when(v.productId()).thenReturn(productId);
        when(v.activeRecipeVersionId()).thenReturn(assignedVersionId);
        return v;
    }

    private void stubVariantOverride(UUID assignedVersionId) {
        ProductVariant v = variantOf(PRODUCT, assignedVersionId);
        when(variants.findByIdAndDeletedAtIsNull(VARIANT)).thenReturn(Optional.of(v));
    }

    private void stubForeignVariantOverride(UUID assignedVersionId) {
        ProductVariant v = variantOf(OTHER_PRODUCT, assignedVersionId);
        when(variants.findByIdAndDeletedAtIsNull(VARIANT)).thenReturn(Optional.of(v));
    }

    private void stubProduct(UUID fallbackVersionId) {
        Product p = mock(Product.class);
        when(p.activeRecipeVersionId()).thenReturn(fallbackVersionId);
        when(products.findById(PRODUCT)).thenReturn(Optional.of(p));
    }

    private void stubFallbackVersion(RecipeStatus status, UUID productId) {
        RecipeVersion fallback = versionOf(status, productId);
        when(recipes.findWithLinesById(FALLBACK_VERSION)).thenReturn(Optional.of(fallback));
    }

    private void stubOverrideVersion(RecipeStatus status, UUID productId) {
        RecipeVersion override = versionOf(status, productId);
        when(recipes.findWithLinesById(OVERRIDE_VERSION)).thenReturn(Optional.of(override));
    }

    @Test
    void variantOverridePointerWinsEvenWhenProductPointsAtAnotherVersion() {
        // The two-pointer capability (2B.1): product fallback and variant override
        // resolve to DIFFERENT versions of the same product simultaneously.
        stubVariantOverride(OVERRIDE_VERSION);
        stubOverrideVersion(RecipeStatus.ACTIVE, PRODUCT);
        stubProduct(FALLBACK_VERSION);

        Resolved r = resolver.resolve(PRODUCT, VARIANT);
        assertEquals(Source.VARIANT, r.source());
        assertTrue(r.present());
        // The product fallback pointer must not even be consulted on a VARIANT hit.
        verify(recipes, never()).findWithLinesById(FALLBACK_VERSION);
    }

    @Test
    void productFallbackPointerResolvesWithoutTouchingVariants() {
        stubProduct(FALLBACK_VERSION);
        stubFallbackVersion(RecipeStatus.ACTIVE, PRODUCT);

        Resolved r = resolver.resolveProduct(PRODUCT);
        assertEquals(Source.PRODUCT, r.source());
        verifyNoInteractions(variants);
    }

    @Test
    void noPointersResolveNone() {
        stubProduct(null);
        Resolved r = resolver.resolveProduct(PRODUCT);
        assertEquals(Source.NONE, r.source());
        assertFalse(r.present());
        verifyNoInteractions(recipes);
    }

    @Test
    void unknownProductResolvesNone() {
        when(products.findById(PRODUCT)).thenReturn(Optional.empty());
        assertEquals(Source.NONE, resolver.resolveProduct(PRODUCT).source());
    }

    @Test
    void variantFromAnotherProductNeverOverrides() {
        stubForeignVariantOverride(OVERRIDE_VERSION);
        stubProduct(FALLBACK_VERSION);
        stubFallbackVersion(RecipeStatus.ACTIVE, PRODUCT);

        assertEquals(Source.PRODUCT, resolver.resolve(PRODUCT, VARIANT).source());
        verify(recipes, never()).findWithLinesById(OVERRIDE_VERSION);
    }

    @Test
    void archivedOverridePointerFallsBackToProductPointer() {
        stubVariantOverride(OVERRIDE_VERSION);
        stubOverrideVersion(RecipeStatus.ARCHIVED, PRODUCT);
        stubProduct(FALLBACK_VERSION);
        stubFallbackVersion(RecipeStatus.ACTIVE, PRODUCT);

        assertEquals(Source.PRODUCT, resolver.resolve(PRODUCT, VARIANT).source());
    }

    @Test
    void productPointerToNonAssignableVersionResolvesNoneNotStatusQuery() {
        // Dangling/stale fallback pointer (superseded/archived/draft) must NOT
        // resurrect via "any ACTIVE version of the product" — assignment only.
        stubProduct(FALLBACK_VERSION);
        stubFallbackVersion(RecipeStatus.SUPERSEDED, PRODUCT);

        assertEquals(Source.NONE, resolver.resolveProduct(PRODUCT).source());
    }

    @Test
    void productPointerToForeignProductVersionIsRejected() {
        stubProduct(FALLBACK_VERSION);
        stubFallbackVersion(RecipeStatus.ACTIVE, OTHER_PRODUCT);

        assertEquals(Source.NONE, resolver.resolveProduct(PRODUCT).source());
    }

    @Test
    void missingOverrideVersionFallsBackToProductPointer() {
        stubVariantOverride(OVERRIDE_VERSION);
        when(recipes.findWithLinesById(OVERRIDE_VERSION)).thenReturn(Optional.empty());
        stubProduct(FALLBACK_VERSION);
        stubFallbackVersion(RecipeStatus.ACTIVE, PRODUCT);

        assertEquals(Source.PRODUCT, resolver.resolve(PRODUCT, VARIANT).source());
    }

    @Test
    void missingVariantFallsBackToProductPointer() {
        when(variants.findByIdAndDeletedAtIsNull(VARIANT)).thenReturn(Optional.empty());
        stubProduct(FALLBACK_VERSION);
        stubFallbackVersion(RecipeStatus.ACTIVE, PRODUCT);

        assertEquals(Source.PRODUCT, resolver.resolve(PRODUCT, VARIANT).source());
    }

    @Test
    void resolutionReadsAreDeterministicSingleLookups() {
        stubVariantOverride(OVERRIDE_VERSION);
        stubOverrideVersion(RecipeStatus.ACTIVE, PRODUCT);
        stubProduct(FALLBACK_VERSION);

        Resolved first = resolver.resolve(PRODUCT, VARIANT);
        Resolved second = resolver.resolve(PRODUCT, VARIANT);
        assertSame(first.recipeVersion(), second.recipeVersion());
        // Exactly one version lookup per resolution: no scanning, no extra queries.
        verify(recipes, times(2)).findWithLinesById(OVERRIDE_VERSION);
        verifyNoMoreInteractions(recipes);
    }

    @Test
    void noProductQueryUsesVariantRepoWhenVariantIdIsNull() {
        stubProduct(null);
        resolver.resolve(PRODUCT, null);
        verifyNoInteractions(variants);
    }
}
