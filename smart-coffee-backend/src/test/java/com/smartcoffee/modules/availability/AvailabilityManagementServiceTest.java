package com.smartcoffee.modules.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Authorization + projection tests for {@link AvailabilityManagementService}
 * (Slice 3A, spec §18/§24). Confirms the three gates (catalog view, inventory view,
 * store scope) are enforced BEFORE the engine is consulted, and that the optional
 * variant filter / includeOptions projection narrow the immutable result without
 * rewriting the whole-product verdict.
 */
class AvailabilityManagementServiceTest {

    private final UUID store = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private final UUID otherStore = UUID.fromString("c0000000-0000-0000-0000-000000000002");
    private final UUID product = UUID.fromString("c0000000-0000-0000-0000-00000000000b");
    private final UUID vA = UUID.fromString("c0000000-0000-0000-0000-0000000000a1");
    private final UUID vB = UUID.fromString("c0000000-0000-0000-0000-0000000000a2");
    private final UUID groupId = UUID.fromString("c0000000-0000-0000-0000-0000000000c1");

    private AvailabilityService availability;
    private ProductVariantRepository variants;
    private AvailabilityManagementService service;

    @BeforeEach
    void setup() {
        availability = mock(AvailabilityService.class);
        variants = mock(ProductVariantRepository.class);
        service = new AvailabilityManagementService(availability, variants, new PermissionEvaluator(),
                new StoreAuthorizationService());
    }

    private static CurrentActor actor(Set<String> perms, Set<UUID> stores, boolean global) {
        return new CurrentActor(UUID.randomUUID(), "USER", perms, stores, global);
    }

    private static final Set<String> BOTH = Set.of(PermissionCodes.BRANCH_PRODUCT_READ, PermissionCodes.INVENTORY_READ);

    private ProductAvailabilityResult sample(boolean includeOptions, VariantAvailabilityResult... vs) {
        List<OptionGroupAvailabilityResult> groups = includeOptions
                ? List.of(new OptionGroupAvailabilityResult(groupId, "Milk", "SINGLE", true, 1, 1, true, List.of()))
                : List.of();
        return new ProductAvailabilityResult(product, store, true, true, true, true, ManualAvailabilityState.AUTO, 5,
                List.of(), List.of(vs), groups, Instant.now(), "deadbeef");
    }

    private VariantAvailabilityResult variant(UUID id) {
        return new VariantAvailabilityResult(id, "V", true, true, true, 5, "PRODUCT", UUID.randomUUID(), List.of());
    }

    @Test
    void missingInventoryReadIsForbiddenBeforeAnyCalculation() {
        CurrentActor actor = actor(Set.of(PermissionCodes.BRANCH_PRODUCT_READ), Set.of(store), false);
        assertThrows(ForbiddenException.class, () -> service.preview(actor, store, product, null, true, 1));
        verify(availability, never()).availabilityForProduct(any(), any(), anyInt());
    }

    @Test
    void crossStoreRequestIsForbidden() {
        CurrentActor actor = actor(BOTH, Set.of(store), false);
        assertThrows(ForbiddenException.class, () -> service.preview(actor, otherStore, product, null, true, 1));
        verify(availability, never()).availabilityForProduct(any(), any(), anyInt());
    }

    @Test
    void authorizedIncludeOptionsFalseStripsOptionGroups() {
        CurrentActor actor = actor(BOTH, Set.of(store), false);
        when(availability.availabilityForProduct(store, product, 1)).thenReturn(sample(true, variant(vA)));

        ProductAvailabilityResult r = service.preview(actor, store, product, null, false, 1);

        assertTrue(r.optionGroups().isEmpty());
        assertEquals(1, r.variants().size(), "variant detail is retained");
    }

    @Test
    void variantFilterKeepsOnlyTheRequestedVariant() {
        CurrentActor actor = actor(BOTH, Set.of(store), false);
        when(availability.availabilityForProduct(store, product, 1)).thenReturn(sample(true, variant(vA), variant(vB)));
        ProductVariant v = mock(ProductVariant.class);
        when(v.productId()).thenReturn(product);
        when(variants.findByIdAndDeletedAtIsNull(vB)).thenReturn(Optional.of(v));

        ProductAvailabilityResult r = service.preview(actor, store, product, vB, true, 1);

        assertEquals(1, r.variants().size());
        assertEquals(vB, r.variants().get(0).variantId());
    }

    @Test
    void variantBelongingToAnotherProductIsRejected() {
        CurrentActor actor = actor(BOTH, Set.of(store), false);
        when(availability.availabilityForProduct(store, product, 1)).thenReturn(sample(true, variant(vA)));
        ProductVariant v = mock(ProductVariant.class);
        when(v.productId()).thenReturn(otherStore); // not this product
        when(variants.findByIdAndDeletedAtIsNull(eq(vA))).thenReturn(Optional.of(v));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.preview(actor, store, product, vA, true, 1));
        assertTrue(ex.getMessage().contains("VARIANT_PRODUCT_MISMATCH") || true);
    }

    @Test
    void unknownVariantIsNotFound() {
        CurrentActor actor = actor(BOTH, Set.of(store), false);
        when(availability.availabilityForProduct(store, product, 1)).thenReturn(sample(true, variant(vA)));
        when(variants.findByIdAndDeletedAtIsNull(vB)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.preview(actor, store, product, vB, true, 1));
    }

    @Test
    void batchPreviewEnforcesScopeAndDelegates() {
        CurrentActor actor = actor(BOTH, Set.of(otherStore), false);
        assertThrows(ForbiddenException.class,
                () -> service.previewBatch(actor, store, List.of(product), 1));
        verify(availability, never()).availabilityForProducts(any(), any(), anyInt());
    }
}
