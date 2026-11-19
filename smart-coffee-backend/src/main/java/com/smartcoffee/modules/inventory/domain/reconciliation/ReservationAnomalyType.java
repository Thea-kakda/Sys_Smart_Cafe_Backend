package com.smartcoffee.modules.inventory.domain.reconciliation;

/**
 * The fixed vocabulary of reservation/balance/ledger consistency checks
 * (Slice 2E, spec §7). Persisted as {@code inventory_reconciliation_findings.anomaly_type}
 * and constrained by {@code ck_recon_finding_anomaly} (V047) — the enum and the
 * CHECK must stay in lock-step.
 *
 * <p>Each type carries a <b>default severity</b> used when a finding is opened.
 * Severity is a property of what was <i>seen</i>; whether a finding is
 * auto-repairable is decided separately from the surrounding evidence
 * ({@link com.smartcoffee.modules.inventory.application.RepairPolicy}), because
 * the same anomaly type can be provably safe to fix in one context and a
 * manual-review case in another. Nothing here "guesses".
 */
public enum ReservationAnomalyType {
    /** recorded reserved_stock != SUM(ACTIVE reservation lines) for store+item. */
    RESERVED_BALANCE_MISMATCH(ReconciliationSeverity.ERROR),
    /** an ACTIVE reservation names a store/item that has no balance row. */
    ACTIVE_RESERVATION_WITHOUT_BALANCE(ReconciliationSeverity.ERROR),
    /** a balance reserves quantity no ACTIVE reservation explains. */
    BALANCE_WITHOUT_ACTIVE_RESERVATION(ReconciliationSeverity.WARNING),
    /** a checked-out cart still shows an ACTIVE cart-owned hold. */
    ACTIVE_CART_RESERVATION_ON_CHECKED_OUT_CART(ReconciliationSeverity.WARNING),
    /** a CONVERTED cart hold names no surviving target order reservation. */
    CONVERTED_RESERVATION_WITHOUT_TARGET(ReconciliationSeverity.CRITICAL),
    /** a CONVERTED cart hold's quantity != the target order reservation's share. */
    CONVERTED_QUANTITY_MISMATCH(ReconciliationSeverity.ERROR),
    /** an ACTIVE order hold on a CANCELLED order. */
    ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER(ReconciliationSeverity.ERROR),
    /** an ACTIVE order hold on a PAID/confirmed order (missed consumption). */
    ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER(ReconciliationSeverity.CRITICAL),
    /** a CONSUMED reservation with no matching SALE_CONSUMPTION ledger row. */
    CONSUMED_RESERVATION_WITHOUT_CONSUMPTION_LEDGER(ReconciliationSeverity.CRITICAL),
    /** more than one SALE_CONSUMPTION names the same reservation. */
    DUPLICATE_CONSUMPTION_LEDGER(ReconciliationSeverity.CRITICAL),
    /** a RELEASED reservation with no SALE_RELEASE explaining the give-back. */
    RELEASED_RESERVATION_WITHOUT_RELEASE_LEDGER(ReconciliationSeverity.WARNING),
    /** both SALE_RELEASE and SALE_CONSUMPTION name the same reservation. */
    RELEASE_AND_CONSUMPTION_CONFLICT(ReconciliationSeverity.CRITICAL),
    /** a reservation whose owner (order/cart) no longer resolves. */
    ORPHAN_RESERVATION(ReconciliationSeverity.ERROR),
    /** a reservation line whose parent reservation no longer resolves. */
    ORPHAN_RESERVATION_LINE(ReconciliationSeverity.ERROR),
    /** a ledger transaction naming a reservation that does not exist. */
    ORPHAN_LEDGER_REFERENCE(ReconciliationSeverity.WARNING),
    /** an ACTIVE reservation already past its expires_at (worker hasn't run). */
    EXPIRED_ACTIVE_RESERVATION(ReconciliationSeverity.WARNING),
    /** current_stock or reserved_stock below zero (DB CHECK should forbid it). */
    NEGATIVE_BALANCE_STATE(ReconciliationSeverity.CRITICAL),
    /** a reservation's store disagrees with its order/cart/line store. */
    RESERVATION_STORE_MISMATCH(ReconciliationSeverity.CRITICAL);

    private final ReconciliationSeverity defaultSeverity;

    ReservationAnomalyType(ReconciliationSeverity defaultSeverity) {
        this.defaultSeverity = defaultSeverity;
    }

    public ReconciliationSeverity defaultSeverity() {
        return defaultSeverity;
    }
}
