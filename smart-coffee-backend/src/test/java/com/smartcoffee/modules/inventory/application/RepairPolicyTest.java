package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.modules.inventory.domain.reconciliation.ReservationAnomalyType;
import org.junit.jupiter.api.Test;

/**
 * Slice 2E repair-policy unit tests (spec §10/§27 item 7). The policy is a pure
 * classifier: it must mark ONLY the two provably-safe actions repairable and every
 * other anomaly manual-only, and it must never throw on a null input. This is the
 * guard that keeps reconciliation from auto-"fixing" a balance it cannot explain.
 */
class RepairPolicyTest {

    private final RepairPolicy policy = new RepairPolicy();

    @Test
    void onlyCancelledOrderReleaseIsAutoRepairable() {
        assertEquals(RepairPolicy.Action.RELEASE_CANCELLED_ORDER,
                policy.classify(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER));
        assertTrue(policy.potentiallyRepairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER));
    }

    @Test
    void paidButUnconsumedIsTheOnlyConsumptionCandidate() {
        assertEquals(RepairPolicy.Action.CONSUME_PAID_ORDER,
                policy.classify(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER));
        assertTrue(policy.potentiallyRepairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER));
    }

    @Test
    void aBalanceMismatchIsNeverAutoRepaired() {
        // The spec forbids "set reservedStock to the calculated sum" without a traceable repair.
        assertEquals(RepairPolicy.Action.MANUAL_ONLY,
                policy.classify(ReservationAnomalyType.RESERVED_BALANCE_MISMATCH));
        assertFalse(policy.potentiallyRepairable(ReservationAnomalyType.RESERVED_BALANCE_MISMATCH));
    }

    @Test
    void ambiguousAndConflictTypesAreManualOnly() {
        assertAll(
                () -> assertEquals(RepairPolicy.Action.MANUAL_ONLY,
                        policy.classify(ReservationAnomalyType.RELEASE_AND_CONSUMPTION_CONFLICT)),
                () -> assertEquals(RepairPolicy.Action.MANUAL_ONLY,
                        policy.classify(ReservationAnomalyType.DUPLICATE_CONSUMPTION_LEDGER)),
                () -> assertEquals(RepairPolicy.Action.MANUAL_ONLY,
                        policy.classify(ReservationAnomalyType.NEGATIVE_BALANCE_STATE)),
                () -> assertEquals(RepairPolicy.Action.MANUAL_ONLY,
                        policy.classify(ReservationAnomalyType.RESERVATION_STORE_MISMATCH)));
    }

    @Test
    void everyAnomalyTypeIsClassifiedDeterministically() {
        for (ReservationAnomalyType type : ReservationAnomalyType.values()) {
            assertNotNull(policy.classify(type), "no anomaly type may classify to null: " + type);
        }
    }

    @Test
    void nullTypeIsManualOnlyNotAnException() {
        assertEquals(RepairPolicy.Action.MANUAL_ONLY, policy.classify(null));
        assertFalse(policy.potentiallyRepairable(null));
    }
}
