package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.modules.inventory.domain.reconciliation.ReservationAnomalyType;
import org.springframework.stereotype.Component;

/**
 * Decides whether a reconciliation finding is safe to repair automatically or
 * must go to a human (Slice 2E, spec §10). This is a pure classifier: it never
 * touches the database and never "guesses". The repair service still re-validates
 * the live state under a lock before acting, and the paid-but-unconsumed case
 * additionally enforces all nine Section-9 conditions at apply time — a proposal
 * to auto-repair here is necessary but not sufficient.
 *
 * <p>Deliberately conservative. Most anomaly types are manual-only because a
 * balance number alone cannot prove intent; only two have a single obviously
 * correct, idempotent action that reuses the existing finalization service:
 * <ul>
 *   <li>{@link Action#RELEASE_CANCELLED_ORDER} — an ACTIVE order hold on an
 *       already-CANCELLED order can only ever be released, and release is a
 *       guarded no-op if it was already terminalised;</li>
 *   <li>{@link Action#CONSUME_PAID_ORDER} — an ACTIVE order hold whose payment is
 *       provably committed but was never consumed; safe only if the nine §9
 *       conditions hold, otherwise it degrades to manual review.</li>
 * </ul>
 */
@Component
public class RepairPolicy {

    public enum Action {
        /** release the cancelled order's hold via the finalization service */
        RELEASE_CANCELLED_ORDER,
        /** consume the paid-but-unconsumed order's hold via the finalization service */
        CONSUME_PAID_ORDER,
        /** no safe automatic action — a human must decide */
        MANUAL_ONLY
    }

    /**
     * @return the single action the reconciliation may attempt for this anomaly,
     *         or {@link Action#MANUAL_ONLY}.
     */
    public Action classify(ReservationAnomalyType type) {
        if (type == null) {
            return Action.MANUAL_ONLY;
        }
        return switch (type) {
            case ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER -> Action.RELEASE_CANCELLED_ORDER;
            case ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER -> Action.CONSUME_PAID_ORDER;
            // Everything else is either ambiguous (checkout state, ledger-vs-snapshot
            // disagreement, cross-store, negative balance, duplicates, release/consume
            // conflict) or a pure reporting backlog the workers already own. We do not
            // auto-"set reservedStock to the calculated sum" without a traceable repair.
            default -> Action.MANUAL_ONLY;
        };
    }

    /** True when this anomaly has a candidate automatic action (still gated at apply). */
    public boolean potentiallyRepairable(ReservationAnomalyType type) {
        return classify(type) != Action.MANUAL_ONLY;
    }
}
