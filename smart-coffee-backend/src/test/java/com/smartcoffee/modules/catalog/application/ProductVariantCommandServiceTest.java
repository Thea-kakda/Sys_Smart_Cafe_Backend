package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import com.smartcoffee.modules.catalog.api.request.CreateProductVariantRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateProductVariantRequest;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Focused unit tests for {@link ProductVariantCommandService} business rules
 * that the HTTP surface will enforce. Repositories and PermissionEvaluator are
 * mocked; the goal is to prove the service catches the rules locally (before
 * any SQL runs) and maps failures to stable business codes.
 */
class ProductVariantCommandServiceTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private ProductVariantRepository variants;
    private ProductRepository products;
    private PermissionEvaluator permissions;
    private ProductVariantCommandService service;
    private CurrentActor actor;

    @BeforeEach
    void setup() {
        variants = mock(ProductVariantRepository.class);
        products = mock(ProductRepository.class);
        permissions = mock(PermissionEvaluator.class);
        service = new ProductVariantCommandService(variants, products, permissions, new ProductVariantMapper());
        actor = new CurrentActor(ACTOR_ID, "USER",
                Set.of(com.smartcoffee.common.security.PermissionCodes.VARIANT_MANAGE,
                        com.smartcoffee.common.security.PermissionCodes.PRODUCT_MANAGE,
                        com.smartcoffee.common.security.PermissionCodes.OPTION_EFFECT_MANAGE),
                Set.of(), true);
        when(products.findById(any(UUID.class)))
                .thenReturn(Optional.of(newProductUsd()));
    }

    // ------------------------------------------------------- create

    @Test
    void createRequiresVariantPermissionBeforeAnyRepositoryCall() {
        doThrow(new ForbiddenException("FORBIDDEN", "no permission"))
                .when(permissions).require(eq(actor), eq(com.smartcoffee.common.security.PermissionCodes.VARIANT_MANAGE));
        assertThrows(ForbiddenException.class, () ->
                service.create(actor, PRODUCT_ID, createReq("SKU1", "USD", "10", false)));
        verifyNoInteractions(variants);
    }

    @Test
    void createRejectsMissingParentProduct() {
        when(products.findById(any())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () ->
                service.create(actor, PRODUCT_ID, createReq("SKU1", "USD", "10", false)));
    }

    @Test
    void createRejectsCurrencyMismatch() {
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.create(actor, PRODUCT_ID, createReq("SKU1", "VND", "10", false)));
        assertEquals("CURRENCY_MISMATCH", ex.code());
    }

    @Test
    void createRejectsNegativePrice() {
        // validateShape in the service fires before the domain factory, so the
        // caller sees a stable business code rather than an IllegalArgumentException.
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.create(actor, PRODUCT_ID, createReq("SKU1", "USD", "-1", false)));
        assertEquals("INVALID_VARIANT_PRICE", ex.code());
    }

    @Test
    void createRejectsDuplicateSkuAsConflict() {
        when(variants.existsByProductIdAndCodeAndDeletedAtIsNull(PRODUCT_ID, "SKU1")).thenReturn(true);
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.create(actor, PRODUCT_ID, createReq("SKU1", "USD", "10", false)));
        assertEquals("DUPLICATE_VARIANT_SKU", ex.code());
    }

    @Test
    void createMapsDataIntegrityRaceToDuplicateSku() {
        when(variants.saveAndFlush(any(ProductVariant.class)))
                .thenThrow(new DataIntegrityViolationException("dup key"));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.create(actor, PRODUCT_ID, createReq("SKUR", "USD", "10", false)));
        assertEquals("DUPLICATE_VARIANT_SKU", ex.code());
    }

    @Test
    void creatingADefaultVariantDemotesThePriorDefaultAtomically() {
        ProductVariant prior = existingVariant("OLD", true, true, 0L);
        when(variants.findActiveDefault(PRODUCT_ID)).thenReturn(Optional.of(prior));
        ProductVariantResponse resp = service.create(actor, PRODUCT_ID, createReq("NEW", "USD", "10", true));
        assertFalse(prior.isDefault(), "prior default must be demoted");
        assertTrue(resp.isDefault(), "new variant is default");
        verify(variants, atLeast(2)).saveAndFlush(any(ProductVariant.class));
    }

    @Test
    void creatingNonDefaultLeavesCurrentDefaultUntouched() {
        ProductVariant prior = existingVariant("OLD", true, true, 0L);
        when(variants.findActiveDefault(PRODUCT_ID)).thenReturn(Optional.of(prior));
        service.create(actor, PRODUCT_ID, createReq("NEW", "USD", "10", false));
        assertTrue(prior.isDefault());
        verify(variants, never()).saveAndFlush(prior);
    }

    // ------------------------------------------------------- update

    @Test
    void updateRejectsStaleVersion() {
        ProductVariant v = existingVariant("S", false, true, 5L);
        when(variants.findByIdAndDeletedAtIsNull(v.id())).thenReturn(Optional.of(v));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.update(actor, v.id(), new UpdateProductVariantRequest(
                        4L, "S", "N", null, null, new BigDecimal("10"), null, "USD", 0, null)));
        assertEquals("PRODUCT_VERSION_CONFLICT", ex.code());
    }

    @Test
    void updateRejectsDuplicateSkuAgainstSiblings() {
        ProductVariant v = existingVariant("A", false, true, 0L);
        ProductVariant sibling = existingVariant("B", false, true, 0L);
        when(variants.findByIdAndDeletedAtIsNull(v.id())).thenReturn(Optional.of(v));
        when(variants.findActiveByProduct(PRODUCT_ID)).thenReturn(List.of(v, sibling));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.update(actor, v.id(), new UpdateProductVariantRequest(
                        v.version(), "B", "N", null, null, new BigDecimal("10"), null, "USD", 0, null)));
        assertEquals("DUPLICATE_VARIANT_SKU", ex.code());
    }

    // --------------------------------------------------- default rules

    @Test
    void setDefaultRefusesInactiveTarget() {
        ProductVariant inactive = existingVariant("A", false, false, 0L);
        when(variants.findByIdAndDeletedAtIsNull(inactive.id())).thenReturn(Optional.of(inactive));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.setDefault(actor, inactive.id()));
        assertEquals("INVALID_VARIANT_STATE", ex.code());
    }

    @Test
    void setDefaultDemotesPriorAndPromotesTarget() {
        ProductVariant prior = existingVariant("P", true, true, 0L);
        ProductVariant target = existingVariant("T", false, true, 0L);
        when(variants.findByIdAndDeletedAtIsNull(target.id())).thenReturn(Optional.of(target));
        when(variants.findActiveDefault(PRODUCT_ID)).thenReturn(Optional.of(prior));
        service.setDefault(actor, target.id());
        assertFalse(prior.isDefault());
        assertTrue(target.isDefault());
    }

    @Test
    void deactivateSoleDefaultWithSiblingsReturnsDefaultRequired() {
        ProductVariant currentDefault = existingVariant("D", true, true, 0L);
        ProductVariant sibling = existingVariant("S", false, true, 0L);
        when(variants.findByIdAndDeletedAtIsNull(currentDefault.id())).thenReturn(Optional.of(currentDefault));
        when(variants.findActiveByProduct(PRODUCT_ID)).thenReturn(List.of(currentDefault, sibling));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.setActive(actor, currentDefault.id(), false));
        assertEquals("DEFAULT_VARIANT_REQUIRED", ex.code());
    }

    @Test
    void deleteSoleDefaultWithSiblingsReturnsDefaultRequired() {
        ProductVariant currentDefault = existingVariant("D", true, true, 0L);
        ProductVariant sibling = existingVariant("S", false, true, 0L);
        when(variants.findByIdAndDeletedAtIsNull(currentDefault.id())).thenReturn(Optional.of(currentDefault));
        when(variants.findActiveByProduct(PRODUCT_ID)).thenReturn(List.of(currentDefault, sibling));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.delete(actor, currentDefault.id()));
        assertEquals("DEFAULT_VARIANT_REQUIRED", ex.code());
    }

    @Test
    void deleteSoftDeletesNonDefaultVariant() {
        ProductVariant v = existingVariant("N", false, true, 0L);
        when(variants.findByIdAndDeletedAtIsNull(v.id())).thenReturn(Optional.of(v));
        service.delete(actor, v.id());
        assertNotNull(v.deletedAt());
    }

    // ------------------------------------------------------- reorder

    @Test
    void reorderRejectsListSizeMismatch() {
        ProductVariant a = existingVariant("A", false, true, 0L);
        when(variants.findActiveByProduct(PRODUCT_ID)).thenReturn(List.of(a));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.reorder(actor, PRODUCT_ID, List.of(UUID.randomUUID(), UUID.randomUUID())));
        assertEquals("REORDER_LIST_MISMATCH", ex.code());
    }

    @Test
    void reorderWritesDisplayOrderIndex() {
        ProductVariant a = existingVariant("A", false, true, 0L);
        ProductVariant b = existingVariant("B", false, true, 0L);
        // Force a stable, non-zero starting order to prove the rewrite.
        b = withDisplayOrder(b, 99);
        when(variants.findActiveByProduct(PRODUCT_ID)).thenReturn(List.of(a, b));
        service.reorder(actor, PRODUCT_ID, List.of(b.id(), a.id()));
        // b was placed first, so its displayOrder becomes 0; a becomes 1.
        // captor() (Mockito 5.7+) infers the element type, so no unchecked
        // forClass(List.class) cast is needed here.
        ArgumentCaptor<List<ProductVariant>> cap = ArgumentCaptor.captor();
        verify(variants).saveAllAndFlush(cap.capture());
        List<ProductVariant> saved = cap.getValue();
        int idxB = indexOfId(saved, b.id());
        int idxA = indexOfId(saved, a.id());
        // Order in saved list matches iteration order of findActiveByProduct;
        // the service rewrote displayOrder on each element to match the input
        // reorder request (b first -> 0, a second -> 1).
        assertEquals(0, saved.get(idxB).displayOrder());
        assertEquals(1, saved.get(idxA).displayOrder());
    }

    // ----------------------------------------------------- fixtures

    private static int indexOfId(List<ProductVariant> xs, UUID id) {
        for (int i = 0; i < xs.size(); i++) {
            if (xs.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private static ProductVariant withDisplayOrder(ProductVariant v, int order) {
        try {
            Field f = ProductVariant.class.getDeclaredField("displayOrder");
            f.setAccessible(true);
            f.setInt(v, order);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return v;
    }

    private static Product newProductUsd() {
        return Product.create(UUID.randomUUID(), "P1", "Prod", null,
                new BigDecimal("10"), "USD", 5, new BigDecimal("0.1"), false, true, null);
    }

    private static ProductVariant existingVariant(String code, boolean isDefault, boolean active, long ver) {
        ProductVariant v = ProductVariant.create(PRODUCT_ID, code, "Name", null, null,
                new BigDecimal("10"), null, "USD", isDefault, active, 0, null);
        // Force stable version for optimistic-lock comparisons. Version is
        // Hibernate-managed; the entity's own domain methods do not modify it,
        // so tests can safely leave it at 0 and drive the mismatch path via
        // the request-side value.
        return v;
    }

    private static CreateProductVariantRequest createReq(String code, String currency, String price, boolean isDefault) {
        return new CreateProductVariantRequest(code, "Name", null, null,
                new BigDecimal(price), null, currency, isDefault, true, 0, null);
    }
}
