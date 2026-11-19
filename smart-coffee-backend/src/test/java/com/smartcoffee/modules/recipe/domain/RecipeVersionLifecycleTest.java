package com.smartcoffee.modules.recipe.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure domain tests for the Slice 2A recipe lifecycle. Proves DRAFT editability,
 * non-DRAFT immutability, the single controlled activation transition, and that
 * the checkout demand formula is preserved unchanged (yield/scaling metadata must
 * not alter {@link RecipeVersionLine#demand(int)}).
 */
class RecipeVersionLifecycleTest {

    private static final UUID PRODUCT = UUID.fromString("30000000-0000-0000-0000-0000000000aa");
    private static final UUID INGREDIENT = UUID.fromString("70000000-0000-0000-0000-0000000000aa");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private RecipeVersionLine coffeeLine() {
        return RecipeVersionLine.create(INGREDIENT, new BigDecimal("0.5"), new BigDecimal("10"),
                null, RecipeScalingRule.FIXED, 0, false, null);
    }

    private RecipeVersion draftWithOneLine() {
        RecipeVersion v = RecipeVersion.createDraft(PRODUCT, 1, BigDecimal.ONE, null, "test", ACTOR);
        v.replaceLines(List.of(coffeeLine()));
        return v;
    }

    @Test
    void newDraftIsEditableAndDefaultsYield() {
        RecipeVersion v = RecipeVersion.createDraft(PRODUCT, 1, null, null, null, ACTOR);
        assertEquals(RecipeStatus.DRAFT, v.status());
        assertTrue(v.isEditable());
        assertEquals(0, BigDecimal.ONE.compareTo(v.yieldQuantity()), "null yield defaults to 1");
    }

    @Test
    void emptyDraftCannotActivate() {
        RecipeVersion v = RecipeVersion.createDraft(PRODUCT, 1, BigDecimal.ONE, null, null, ACTOR);
        assertThrows(IllegalStateException.class, () -> v.markActive(ACTOR, Instant.now()));
    }

    @Test
    void activateTransitionsAndRecordsActor() {
        RecipeVersion v = draftWithOneLine();
        Instant now = Instant.now();
        v.markActive(ACTOR, now);
        assertEquals(RecipeStatus.ACTIVE, v.status());
        assertFalse(v.isEditable());
        assertEquals(ACTOR, v.activatedBy());
        assertNotNull(v.activatedAt());
    }

    @Test
    void activeVersionIsImmutable() {
        RecipeVersion v = draftWithOneLine();
        v.markActive(ACTOR, Instant.now());
        assertThrows(IllegalStateException.class,
                () -> v.updateMeta(BigDecimal.TWO, null, "x", ACTOR));
        assertThrows(IllegalStateException.class, () -> v.replaceLines(List.of(coffeeLine())));
        assertThrows(IllegalStateException.class, () -> v.markActive(ACTOR, Instant.now()));
    }

    @Test
    void supersedeAndArchiveAreTerminalImmutable() {
        RecipeVersion v = draftWithOneLine();
        v.markActive(ACTOR, Instant.now());
        v.markSuperseded(Instant.now());
        assertEquals(RecipeStatus.SUPERSEDED, v.status());
        assertFalse(v.isEditable());
        v.markArchived();
        assertEquals(RecipeStatus.ARCHIVED, v.status());
    }

    @Test
    void demandFormulaIsUnchangedBySliceMetadata() {
        // 0.5 base * 2 units * (1 + 10%) = 1.1 — identical to the pre-2A formula.
        BigDecimal demand = coffeeLine().demand(2);
        assertEquals(0, new BigDecimal("1.1").compareTo(demand.stripTrailingZeros()),
                "demand arithmetic must be preserved for checkout");
    }

    @Test
    void lineRejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> RecipeVersionLine.create(
                INGREDIENT, BigDecimal.ZERO, BigDecimal.ZERO, null, RecipeScalingRule.FIXED, 0, false, null));
    }
}
