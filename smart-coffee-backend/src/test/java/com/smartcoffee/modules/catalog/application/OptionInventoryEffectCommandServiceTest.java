package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.InventoryEffectResponse;
import com.smartcoffee.modules.catalog.api.request.SetInventoryEffectRequest;
import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import com.smartcoffee.modules.catalog.persistence.CustomizationOptionRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link OptionInventoryEffectCommandService}. Proves
 * the source/target split, per-type required fields, optimistic locking and
 * permission gating all fire before persistence.
 */
class OptionInventoryEffectCommandServiceTest {

    private CustomizationOptionRepository repo;
    private PermissionEvaluator permissions;
    private OptionInventoryEffectCommandService service;
    private CurrentActor actor;

    @BeforeEach
    void setup() {
        repo = mock(CustomizationOptionRepository.class);
        permissions = mock(PermissionEvaluator.class);
        service = new OptionInventoryEffectCommandService(repo, permissions, new ProductVariantMapper());
        actor = new CurrentActor(UUID.randomUUID(), "USER",
                Set.of(PermissionCodes.OPTION_EFFECT_MANAGE), Set.of(), true);
    }

    @Test
    void missingPermissionStopsBeforeAnyLookup() {
        doThrow(new ForbiddenException("FORBIDDEN", "no permission"))
                .when(permissions).require(eq(actor), eq(PermissionCodes.OPTION_EFFECT_MANAGE));
        assertThrows(ForbiddenException.class, () ->
                service.set(actor, UUID.randomUUID(), null,
                        new SetInventoryEffectRequest(InventoryEffectType.NONE, null, null, null, null, null)));
        verifyNoInteractions(repo);
    }

    @Test
    void unknownOptionYieldsNotFound() {
        when(repo.findById(any())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () ->
                service.set(actor, UUID.randomUUID(), null,
                        new SetInventoryEffectRequest(InventoryEffectType.NONE, null, null, null, null, null)));
    }

    @Test
    void staleVersionReturnsConflict() throws Exception {
        CustomizationOption o = newOption(InventoryEffectType.NONE, 3L);
        when(repo.findById(o.id())).thenReturn(Optional.of(o));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.set(actor, o.id(), 2L,
                        new SetInventoryEffectRequest(InventoryEffectType.NONE, null, null, null, null, null)));
        assertEquals("OPTION_VERSION_CONFLICT", ex.code());
    }

    @Test
    void removeWithoutSourceIsRejected() throws Exception {
        CustomizationOption o = newOption(InventoryEffectType.NONE, 0L);
        when(repo.findById(o.id())).thenReturn(Optional.of(o));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.set(actor, o.id(), null,
                        new SetInventoryEffectRequest(InventoryEffectType.REMOVE, null, null, null, null, null)));
        assertEquals("SOURCE_INVENTORY_ITEM_REQUIRED", ex.code());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void multiplyWithoutSourceIsRejected() throws Exception {
        CustomizationOption o = newOption(InventoryEffectType.NONE, 0L);
        when(repo.findById(o.id())).thenReturn(Optional.of(o));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.set(actor, o.id(), null,
                        new SetInventoryEffectRequest(InventoryEffectType.MULTIPLY, null, null,
                                null, new BigDecimal("0.50"), null)));
        assertEquals("SOURCE_INVENTORY_ITEM_REQUIRED", ex.code());
    }

    @Test
    void multiplyWithoutMultiplierIsRejected() throws Exception {
        CustomizationOption o = newOption(InventoryEffectType.NONE, 0L);
        when(repo.findById(o.id())).thenReturn(Optional.of(o));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.set(actor, o.id(), null,
                        new SetInventoryEffectRequest(InventoryEffectType.MULTIPLY, UUID.randomUUID(), null,
                                null, null, null)));
        assertEquals("MULTIPLIER_REQUIRED", ex.code());
    }

    @Test
    void addTargetRequired() throws Exception {
        CustomizationOption o = newOption(InventoryEffectType.NONE, 0L);
        when(repo.findById(o.id())).thenReturn(Optional.of(o));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.set(actor, o.id(), null,
                        new SetInventoryEffectRequest(InventoryEffectType.ADD, null, null,
                                new BigDecimal("5"), null, null)));
        assertEquals("TARGET_INVENTORY_ITEM_REQUIRED", ex.code());
    }

    @Test
    void replaceSameSourceAndTargetRejected() throws Exception {
        CustomizationOption o = newOption(InventoryEffectType.NONE, 0L);
        UUID ing = UUID.randomUUID();
        when(repo.findById(o.id())).thenReturn(Optional.of(o));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.set(actor, o.id(), null,
                        new SetInventoryEffectRequest(InventoryEffectType.REPLACE, ing, ing, null, null, null)));
        assertEquals("SOURCE_AND_TARGET_ITEM_SAME", ex.code());
    }

    @Test
    void replaceMissingSourceRejected() throws Exception {
        CustomizationOption o = newOption(InventoryEffectType.NONE, 0L);
        when(repo.findById(o.id())).thenReturn(Optional.of(o));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.set(actor, o.id(), null,
                        new SetInventoryEffectRequest(InventoryEffectType.REPLACE, null, UUID.randomUUID(),
                                null, null, null)));
        assertEquals("INVALID_INVENTORY_EFFECT", ex.code());
    }

    @Test
    void validMultiplyPersists() throws Exception {
        CustomizationOption o = newOption(InventoryEffectType.NONE, 0L);
        when(repo.findById(o.id())).thenReturn(Optional.of(o));
        UUID src = UUID.randomUUID();
        InventoryEffectResponse resp = service.set(actor, o.id(), null,
                new SetInventoryEffectRequest(InventoryEffectType.MULTIPLY, src, null, null,
                        new BigDecimal("0.50"), null));
        assertEquals(InventoryEffectType.MULTIPLY, resp.type());
        assertEquals(src, resp.sourceIngredientId());
        assertNull(resp.targetIngredientId());
        assertEquals(0, new BigDecimal("0.50").compareTo(resp.effectMultiplier()));
        verify(repo).saveAndFlush(o);
    }

    @Test
    @SuppressWarnings("deprecation") // legacy V039 field: clear() must still reset it
    void clearResetsEveryAuxiliaryField() throws Exception {
        CustomizationOption o = newOption(InventoryEffectType.NONE, 0L);
        o.applyInventoryEffect(InventoryEffectType.ADD, null, UUID.randomUUID(),
                new BigDecimal("18"), null, null);
        when(repo.findById(o.id())).thenReturn(Optional.of(o));
        InventoryEffectResponse resp = service.clear(actor, o.id(), null);
        assertEquals(InventoryEffectType.NONE, resp.type());
        assertNull(resp.sourceIngredientId());
        assertNull(resp.targetIngredientId());
        assertNull(resp.effectQuantity());
        assertNull(resp.effectMultiplier());
        assertNull(resp.replacesOptionId());
    }

    private static CustomizationOption newOption(InventoryEffectType type, long version) throws Exception {
        var ctor = CustomizationOption.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CustomizationOption o = ctor.newInstance();
        set(o, "id", UUID.randomUUID());
        set(o, "groupId", UUID.randomUUID());
        set(o, "code", "o");
        set(o, "name", "Opt");
        set(o, "additionalPrice", BigDecimal.ZERO);
        set(o, "displayOrder", 0);
        set(o, "active", true);
        set(o, "inventoryEffectType", type);
        set(o, "version", version);
        return o;
    }

    private static void set(Object t, String name, Object v) throws Exception {
        Field f = t.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(t, v);
    }
}
