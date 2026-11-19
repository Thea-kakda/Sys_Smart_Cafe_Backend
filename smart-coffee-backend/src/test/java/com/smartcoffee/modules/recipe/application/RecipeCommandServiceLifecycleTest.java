package com.smartcoffee.modules.recipe.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
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

/**
 * Slice 2B.1 unit rules for {@link RecipeCommandService}: activation is a pure
 * DRAFT -&gt; ACTIVE publication that touches NOTHING else (no supersede loop,
 * no product/variant repository interaction at all), and archiving is refused
 * while ANY assignment pointer still references the version
 * (RECIPE_VERSION_IN_USE) — assignment is never silently cleared.
 */
class RecipeCommandServiceLifecycleTest {

    private static final UUID PRODUCT = UUID.fromString("30000000-0000-0000-0000-0000000000aa");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private RecipeVersionRepository recipes;
    private ProductRepository products;
    private ProductVariantRepository variants;
    private RecipeCommandService service;
    private final CurrentActor actor =
            new CurrentActor(ACTOR_ID, "USER", Set.of("recipe:activate"), Set.of(), true);

    @BeforeEach
    void wire() {
        recipes = mock(RecipeVersionRepository.class);
        products = mock(ProductRepository.class);
        variants = mock(ProductVariantRepository.class);
        service = new RecipeCommandService(recipes, products, variants,
                mock(PermissionEvaluator.class), mock(RecipeItemValidator.class), new RecipeMapper());
    }

    private RecipeVersion version(RecipeStatus status) {
        RecipeVersion v = RecipeVersion.createDraft(PRODUCT, 1, BigDecimal.ONE, null, "u", ACTOR_ID);
        v.replaceLines(List.of(RecipeVersionLine.create(
                UUID.fromString("70000000-0000-0000-0000-0000000000aa"), new BigDecimal("1"),
                BigDecimal.ZERO, null, RecipeScalingRule.FIXED, 0, false, null)));
        switch (status) {
            case ACTIVE -> v.markActive(ACTOR_ID, Instant.now());
            case SUPERSEDED -> {
                v.markActive(ACTOR_ID, Instant.now());
                v.markSuperseded(Instant.now());
            }
            case ARCHIVED -> {
                v.markActive(ACTOR_ID, Instant.now());
                v.markArchived();
            }
            default -> {
            }
        }
        return v;
    }

    private void stubFind(RecipeVersion v) {
        when(recipes.findWithLinesById(v.id())).thenReturn(Optional.of(v));
    }

    // --------------------------------------------------------------- activate

    @Test
    void activatePublishesWithoutTouchingAnyOtherVersion() {
        RecipeVersion draft = version(RecipeStatus.DRAFT);
        stubFind(draft);
        when(recipes.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        var r = service.activate(actor, draft.id(), draft.version());
        assertEquals(RecipeStatus.ACTIVE, r.status());
        // The 2B.1 proof: publication performs ZERO product/variant repository
        // work — no supersede sweep, no pointer cascade, no lock query.
        verifyNoInteractions(products);
        verifyNoInteractions(variants);
        verify(recipes, times(1)).saveAndFlush(any());
    }

    @Test
    void activateRefusesAlreadyPublishedVersion() {
        RecipeVersion active = version(RecipeStatus.ACTIVE);
        stubFind(active);
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.activate(actor, active.id(), active.version()));
        assertEquals("RECIPE_VERSION_IMMUTABLE", ex.code());
    }

    // ----------------------------------------------------------------- archive

    @Test
    void archiveRefusedWhileProductFallbackPointsAtVersion() {
        RecipeVersion active = version(RecipeStatus.ACTIVE);
        stubFind(active);
        when(products.existsByActiveRecipeVersionId(active.id())).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.archive(actor, active.id(), active.version()));
        assertEquals("RECIPE_VERSION_IN_USE", ex.code());
        assertEquals(RecipeStatus.ACTIVE, active.status(), "refusal must not mutate the version");
        verify(recipes, never()).saveAndFlush(any());
    }

    @Test
    void archiveRefusedWhileVariantOverridePointsAtVersion() {
        RecipeVersion active = version(RecipeStatus.ACTIVE);
        stubFind(active);
        when(products.existsByActiveRecipeVersionId(active.id())).thenReturn(false);
        when(variants.findAssignedToRecipe(active.id())).thenReturn(List.of(
                com.smartcoffee.modules.catalog.domain.ProductVariant.create(
                        PRODUCT, "V1", "L", null, null, BigDecimal.ONE, null, "USD", false, true, 0, null)));

        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.archive(actor, active.id(), active.version()));
        assertEquals("RECIPE_VERSION_IN_USE", ex.code());
        verify(recipes, never()).saveAndFlush(any());
    }

    @Test
    void archiveAllowedOnceUnassigned() {
        RecipeVersion active = version(RecipeStatus.ACTIVE);
        stubFind(active);
        when(products.existsByActiveRecipeVersionId(active.id())).thenReturn(false);
        when(variants.findAssignedToRecipe(active.id())).thenReturn(List.of());
        when(recipes.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        var r = service.archive(actor, active.id(), active.version());
        assertEquals(RecipeStatus.ARCHIVED, r.status());
    }

    @Test
    void archiveOfAlreadyArchivedIsImmutable() {
        RecipeVersion archived = version(RecipeStatus.ARCHIVED);
        stubFind(archived);
        ConflictException ex = assertThrows(ConflictException.class, () ->
                service.archive(actor, archived.id(), archived.version()));
        assertEquals("RECIPE_VERSION_IMMUTABLE", ex.code());
    }
}
