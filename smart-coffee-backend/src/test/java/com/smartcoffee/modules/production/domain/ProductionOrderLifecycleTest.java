package com.smartcoffee.modules.production.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage for the {@link ProductionOrder} aggregate state graph
 * (Slice 5A §4, Phase 5A.1). No database, no Spring — the aggregate enforces
 * its invariants in isolation so a bug in the future command-service wiring
 * cannot rewrite history behind the domain's back.
 *
 * <p>Cases mirror spec §24 domain-test list:
 * <ol>
 *   <li>DRAFT opens with the required fields and rejects blanks.</li>
 *   <li>Non-positive planned output quantity fails at factory time.</li>
 *   <li>DRAFT is the only editable state (updateDraft / markPlanned).</li>
 *   <li>markPlanned freezes recipe + material + output snapshot exactly once.</li>
 *   <li>Start requires PLANNED and records actor/time.</li>
 *   <li>Complete requires IN_PROGRESS (reserved for Phase 5A.2, guards tested now).</li>
 *   <li>Cancel refuses after COMPLETED / CLOSED, is idempotent on CANCELLED.</li>
 *   <li>Close only from COMPLETED.</li>
 *   <li>Every transition bumps {@code updatedAt}; the JPA {@code @Version}
 *       column is owned by Hibernate and is exercised by the concurrency ITs,
 *       so we assert only the field-accessor contract here.</li>
 * </ol>
 */
class ProductionOrderLifecycleTest {

    private static final UUID STORE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OUTPUT_ITEM = UUID.randomUUID();
    private static final UUID RECIPE_VERSION = UUID.randomUUID();
    private static final BigDecimal QTY = new BigDecimal("10");

    // ------------------------------------------------------------- factory
    private ProductionOrder draft() {
        return ProductionOrder.createDraft("PO-20261026-000001", STORE, PRODUCT, null,
                QTY, null, null, "baseline draft", ACTOR);
    }

    private ProductionOrderMaterial aMaterial() {
        return ProductionOrderMaterial.plan(UUID.randomUUID(), new BigDecimal("0.5000"), "G",
                null, BigDecimal.ZERO, 0);
    }

    private ProductionOrderOutput anOutput() {
        return ProductionOrderOutput.plan(OUTPUT_ITEM, QTY, "EA");
    }

    private ProductionOrder planned() {
        var o = draft();
        o.markPlanned(RECIPE_VERSION, 1, OUTPUT_ITEM, "EA", List.of(aMaterial()), anOutput(), ACTOR);
        return o;
    }

    // ----------------------------------------------------------- behaviour
    @Test
    void createDraftOpensInDraftWithServerGeneratedFields() {
        var o = draft();
        assertEquals(ProductionOrderStatus.DRAFT, o.status());
        assertEquals("PO-20261026-000001", o.number());
        assertEquals(STORE, o.storeId());
        assertEquals(PRODUCT, o.productId());
        assertNull(o.variantId());
        assertEquals(0, QTY.compareTo(o.plannedOutputQuantity()));
        assertNotNull(o.id());
        assertNotNull(o.createdAt());
        assertNotNull(o.updatedAt());
        assertEquals(ACTOR, o.createdBy());
        assertEquals(ACTOR, o.updatedBy());
        assertNull(o.recipeVersionId());
        assertTrue(o.materials().isEmpty());
        assertTrue(o.outputs().isEmpty());
        assertTrue(o.waste().isEmpty());
    }

    @Test
    void createDraftRejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.createDraft(null, STORE, PRODUCT, null,
                QTY, null, null, null, ACTOR));
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.createDraft("  ", STORE, PRODUCT, null,
                QTY, null, null, null, ACTOR));
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.createDraft("PO-1", null, PRODUCT, null,
                QTY, null, null, null, ACTOR));
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.createDraft("PO-1", STORE, null, null,
                QTY, null, null, null, ACTOR));
    }

    @Test
    void createDraftRejectsNonPositivePlannedQuantity() {
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.createDraft("PO-1", STORE, PRODUCT, null,
                BigDecimal.ZERO, null, null, null, ACTOR));
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.createDraft("PO-1", STORE, PRODUCT, null,
                new BigDecimal("-1"), null, null, null, ACTOR));
        assertThrows(IllegalArgumentException.class, () -> ProductionOrder.createDraft("PO-1", STORE, PRODUCT, null,
                null, null, null, null, ACTOR));
    }

    @Test
    void updateDraftOnlySucceedsInDraft() {
        var o = draft();
        o.updateDraft(PRODUCT, null, new BigDecimal("20"), null, Instant.now(), "edited", ACTOR);
        assertEquals(0, new BigDecimal("20").compareTo(o.plannedOutputQuantity()));
        assertEquals("edited", o.notes());

        var planned = planned();
        assertThrows(IllegalStateException.class, () -> planned.updateDraft(PRODUCT, null, QTY, null, null, null,
                ACTOR), "PLANNED is immutable metadata (spec §5)");
    }

    @Test
    void updateDraftRejectsZeroQuantity() {
        var o = draft();
        assertThrows(IllegalArgumentException.class,
                () -> o.updateDraft(PRODUCT, null, BigDecimal.ZERO, null, null, null, ACTOR));
        assertThrows(IllegalArgumentException.class,
                () -> o.updateDraft(null, null, QTY, null, null, null, ACTOR));
    }

    @Test
    void markPlannedFreezesRecipeMaterialAndOutputSnapshot() {
        var o = draft();
        var m1 = aMaterial();
        var m2 = aMaterial();
        var out = anOutput();
        o.markPlanned(RECIPE_VERSION, 3, OUTPUT_ITEM, "EA", List.of(m1, m2), out, ACTOR);

        assertEquals(ProductionOrderStatus.PLANNED, o.status());
        assertEquals(RECIPE_VERSION, o.recipeVersionId());
        assertEquals(3, o.recipeVersionNumber());
        assertEquals(2, o.materials().size());
        assertEquals(1, o.outputs().size());
        assertEquals(OUTPUT_ITEM, o.outputs().get(0).outputInventoryItemId());
        // children are re-parented via attach() (package-private) — assert the copy is immutable.
        List<ProductionOrderMaterial> snapshot = o.materials();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(aMaterial()));
    }

    @Test
    void markPlannedRejectsMissingPlanningFields() {
        var o = draft();
        assertThrows(IllegalStateException.class, () -> o.markPlanned(null, 1, OUTPUT_ITEM, "EA",
                List.of(aMaterial()), anOutput(), ACTOR));
        assertThrows(IllegalStateException.class, () -> o.markPlanned(RECIPE_VERSION, 1, null, "EA",
                List.of(aMaterial()), anOutput(), ACTOR));
        assertThrows(IllegalStateException.class, () -> o.markPlanned(RECIPE_VERSION, 1, OUTPUT_ITEM, "EA",
                List.of(), anOutput(), ACTOR));
        assertThrows(IllegalStateException.class, () -> o.markPlanned(RECIPE_VERSION, 1, OUTPUT_ITEM, "EA",
                List.of(aMaterial()), null, ACTOR));
    }

    @Test
    void markPlannedOnlyFromDraft() {
        var planned = planned();
        assertThrows(IllegalStateException.class, () -> planned.markPlanned(RECIPE_VERSION, 1, OUTPUT_ITEM, "EA",
                List.of(aMaterial()), anOutput(), ACTOR),
                "the planning snapshot is written exactly once (spec §5)");
    }

    @Test
    void startRequiresPlannedAndRecordsActor() {
        assertThrows(IllegalStateException.class, () -> draft().markStarted(ACTOR),
                "DRAFT cannot jump straight to IN_PROGRESS");

        var o = planned();
        o.markStarted(ACTOR);
        assertEquals(ProductionOrderStatus.IN_PROGRESS, o.status());
        assertEquals(ACTOR, o.startedBy());
        assertNotNull(o.startedAt());
        assertNull(o.completedAt(), "Phase 5A.1 start performs no completion bookkeeping");
    }

    @Test
    void startIsNotRepeatable() {
        var o = planned();
        o.markStarted(ACTOR);
        assertThrows(IllegalStateException.class, () -> o.markStarted(ACTOR));
    }

    @Test
    void completeRequiresInProgress() {
        var o = planned();
        assertThrows(IllegalStateException.class, () -> o.markCompleted(new BigDecimal("10"), ACTOR),
                "PLANNED cannot complete directly (spec §9)");
        o.markStarted(ACTOR);
        o.markCompleted(new BigDecimal("10"), ACTOR);
        assertEquals(ProductionOrderStatus.COMPLETED, o.status());
        assertEquals(ACTOR, o.completedBy());
        assertNotNull(o.completedAt());
        assertEquals(0, new BigDecimal("10").compareTo(o.actualOutputQuantity()));
    }

    @Test
    void completeRejectsNegativeActualOutput() {
        var o = planned();
        o.markStarted(ACTOR);
        assertThrows(IllegalArgumentException.class, () -> o.markCompleted(new BigDecimal("-1"), ACTOR));
        assertThrows(IllegalArgumentException.class, () -> o.markCompleted(null, ACTOR));
    }

    @Test
    void cancelAllowedFromDraftPlannedInProgress() {
        for (ProductionOrder order : List.of(draft(), planned(), startedOrder())) {
            order.cancel(ACTOR, "no longer needed");
            assertEquals(ProductionOrderStatus.CANCELLED, order.status());
            assertEquals(ACTOR, order.cancelledBy());
            assertNotNull(order.cancelledAt());
            assertEquals("no longer needed", order.cancellationReason());
        }
    }

    @Test
    void cancelRefusesAfterCompletedAndClosed() {
        var done = completedOrder();
        assertThrows(IllegalStateException.class, () -> done.cancel(ACTOR, "too late"));

        done.close(ACTOR, null);
        assertThrows(IllegalStateException.class, () -> done.cancel(ACTOR, "still too late"));
    }

    @Test
    void cancelIsIdempotentWhenAlreadyCancelled() {
        var o = draft();
        o.cancel(ACTOR, "first");
        Instant first = o.cancelledAt();
        o.cancel(ACTOR, "second attempt");
        assertEquals(first, o.cancelledAt(), "duplicate cancel must not rewrite cancellation metadata");
        assertEquals("first", o.cancellationReason());
    }

    @Test
    void cancelRequiresReason() {
        var o = draft();
        assertThrows(IllegalArgumentException.class, () -> o.cancel(ACTOR, null));
        assertThrows(IllegalArgumentException.class, () -> o.cancel(ACTOR, "  "));
    }

    @Test
    void closeOnlyFromCompletedAndIsTerminal() {
        var inProgress = startedOrder();
        assertThrows(IllegalStateException.class, () -> inProgress.close(ACTOR, "early"),
                "spec §17: close only from COMPLETED");

        var done = completedOrder();
        done.close(ACTOR, "accepted");
        assertEquals(ProductionOrderStatus.CLOSED, done.status());
        assertEquals(ACTOR, done.closedBy());
        assertEquals("accepted", done.closeNote());
        assertNotNull(done.closedAt());

        assertThrows(IllegalStateException.class, () -> done.close(ACTOR, "again"),
                "CLOSED is terminal (spec §17)");
        assertThrows(IllegalStateException.class, () -> done.markCompleted(QTY, ACTOR));
    }

    @Test
    void closeAllowsBlankNoteToNull() {
        var done = completedOrder();
        done.close(ACTOR, "   ");
        assertNull(done.closeNote());
    }

    private ProductionOrder startedOrder() {
        var o = planned();
        o.markStarted(ACTOR);
        return o;
    }

    private ProductionOrder completedOrder() {
        var o = startedOrder();
        o.markCompleted(QTY, ACTOR);
        return o;
    }
}
