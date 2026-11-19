package com.smartcoffee.modules.recipe.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.domain.StockMode;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.recipe.api.ProductRecipeResponse;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.AssignVariantRecipeRequest;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.domain.RecipeVersionLine;
import com.smartcoffee.modules.recipe.persistence.RecipeVersionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Slice 2B.1 unit rules for {@link RecipeAssignmentService}: the product
 * fallback pointer is the ONLY product-level selector, assignment requires an
 * ACTIVE (published) version owned by the same product, stale optimistic-lock
 * echoes conflict (409 semantics), RECIPE_BASED products may never clear their
 * fallback, and the variant path keeps its legacy mismatch code. Real domain
 * objects are used wherever behaviour is asserted; mocks only at repository
 * boundaries.
 */
class RecipeAssignmentServiceTest {

    private static final UUID PRODUCT = UUID.fromString("30000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_PRODUCT = UUID.fromString("30000000-0000-0000-0000-0000000000cc");
    private static final UUID VERSION = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private ProductRepository products;
    private ProductVariantRepository variants;
    private RecipeVersionRepository recipes;
    private RecipeAssignmentService service;
    private final CurrentActor actor =
            new CurrentActor(ACTOR_ID, "USER", Set.of("recipe:assign"), Set.of(), true);

    @BeforeEach
    void wire() {
        products = mock(ProductRepository.class);
        variants = mock(ProductVariantRepository.class);
        recipes = mock(RecipeVersionRepository.class);
        service = new RecipeAssignmentService(variants, products, recipes,
                mock(PermissionEvaluator.class), new RecipeMapper());
    }

    private Product product() {
        return Product.create(UUID.fromString("40000000-0000-0000-0000-000000000001"), "P1", "Latte",
                null, new BigDecimal("10"), "USD", 5, new BigDecimal("0.1"), false, true, null);
    }

    private RecipeVersion published(RecipeStatus status, UUID productId) {
        RecipeVersion v = RecipeVersion.createDraft(productId, 1, BigDecimal.ONE, null, "u", ACTOR_ID);
        v.replaceLines(List.of(RecipeVersionLine.create(
                UUID.fromString("70000000-0000-0000-0000-0000000000aa"), new BigDecimal("1"),
                BigDecimal.ZERO, null, RecipeScalingRule.FIXED, 0, false, null)));
        if (status == RecipeStatus.ACTIVE) {
            v.markActive(ACTOR_ID, Instant.now());
        }
        return v;
    }

    // ----------------------------------------------------------- product path

    @Test
    void assignProductFallbackPointsAtPublishedVersionOfSameProduct() {
        Product p = product();
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        when(products.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        RecipeVersion rv = published(RecipeStatus.ACTIVE, p.id());
        when(recipes.findWithLinesById(rv.id())).thenReturn(Optional.of(rv));

        ProductRecipeResponse r = service.assignProduct(actor, p.id(),
                new AssignProductRecipeRequest(rv.id(), p.version()));
        assertEquals(rv.id(), r.activeRecipeVersionId());
        assertEquals(p.id(), r.productId());
    }

    @Test
    void clearProductFallbackSucceedsForMadeToOrder() {
        Product p = product();
        p.assignRecipeVersion(VERSION);
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        when(products.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        ProductRecipeResponse r = service.assignProduct(actor, p.id(),
                new AssignProductRecipeRequest(null, p.version()));
        assertNull(r.activeRecipeVersionId());
    }

    @Test
    void recipeBasedProductMayNeverClearItsFallback() {
        Product p = mock(Product.class);
        when(p.id()).thenReturn(PRODUCT);
        when(products.findById(PRODUCT)).thenReturn(Optional.of(p));
        when(p.stockMode()).thenReturn(StockMode.RECIPE_BASED);
        when(p.version()).thenReturn(0L);

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.assignProduct(actor, PRODUCT, new AssignProductRecipeRequest(null, 0L)));
        assertEquals("PRODUCT_RECIPE_REQUIRED", ex.code());
        verify(p, never()).clearRecipeVersion();
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void draftVersionIsNotAssignable() {
        Product p = product();
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        RecipeVersion rv = published(RecipeStatus.DRAFT, p.id());
        when(recipes.findWithLinesById(rv.id())).thenReturn(Optional.of(rv));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.assignProduct(actor, p.id(), new AssignProductRecipeRequest(rv.id(), p.version())));
        assertEquals("RECIPE_VERSION_NOT_ACTIVE", ex.code());
    }

    @Test
    void foreignProductVersionIsRejectedWithProductScopedCode() {
        Product p = product();
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        RecipeVersion rv = published(RecipeStatus.ACTIVE, OTHER_PRODUCT);
        when(recipes.findWithLinesById(rv.id())).thenReturn(Optional.of(rv));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.assignProduct(actor, p.id(), new AssignProductRecipeRequest(rv.id(), p.version())));
        assertEquals("PRODUCT_RECIPE_MISMATCH", ex.code());
    }

    @Test
    void staleProductEchoConflictsBeforeAnyLookup() {
        Product p = product();
        when(products.findById(p.id())).thenReturn(Optional.of(p));

        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.assignProduct(actor, p.id(), new AssignProductRecipeRequest(VERSION, 999L)));
        assertEquals("PRODUCT_VERSION_CONFLICT", ex.code());
        verify(recipes, never()).findWithLinesById(any());
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void lostUpdateAtSaveSurfaceAsProductConflict() {
        Product p = product();
        when(products.findById(p.id())).thenReturn(Optional.of(p));
        RecipeVersion rv = published(RecipeStatus.ACTIVE, p.id());
        when(recipes.findWithLinesById(rv.id())).thenReturn(Optional.of(rv));
        when(products.saveAndFlush(any())).thenThrow(
                new ObjectOptimisticLockingFailureException(Product.class, "x"));

        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.assignProduct(actor, p.id(), new AssignProductRecipeRequest(rv.id(), p.version())));
        assertEquals("PRODUCT_VERSION_CONFLICT", ex.code());
    }

    @Test
    void unknownProductIsNotFound() {
        when(products.findById(PRODUCT)).thenReturn(Optional.empty());
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () ->
                service.assignProduct(actor, PRODUCT, new AssignProductRecipeRequest(VERSION, 0L)));
        assertEquals("PRODUCT_NOT_FOUND", ex.code());
    }

    // ------------------------------------------------------------ variant path

    @Test
    void variantPathKeepsLegacyMismatchCodeForApiCompatibility() {
        ProductVariant v = ProductVariant.create(PRODUCT, "V1", "Large", null, null,
                new BigDecimal("14"), null, "USD", false, true, 0, null);
        RecipeVersion rv = published(RecipeStatus.ACTIVE, OTHER_PRODUCT);
        when(variants.findByIdAndDeletedAtIsNull(v.id())).thenReturn(Optional.of(v));
        when(recipes.findWithLinesById(rv.id())).thenReturn(Optional.of(rv));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.assign(actor, v.id(), new AssignVariantRecipeRequest(rv.id(), v.version())));
        assertEquals("VARIANT_RECIPE_PRODUCT_MISMATCH", ex.code());
    }

    @Test
    void variantClearIsAlwaysAllowedEvenForRecipeBasedProducts() {
        // Overrides are optional by definition; only the product fallback can be
        // load-bearing (RECIPE_BASED), so the variant path must not refuse null.
        ProductVariant v = ProductVariant.create(PRODUCT, "V1", "Large", null, null,
                new BigDecimal("14"), null, "USD", false, true, 0, null);
        v.assignRecipeVersion(VERSION);
        when(variants.findByIdAndDeletedAtIsNull(v.id())).thenReturn(Optional.of(v));
        when(variants.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        var r = service.assign(actor, v.id(), new AssignVariantRecipeRequest(null, v.version()));
        assertNull(r.activeRecipeVersionId());
    }
}
