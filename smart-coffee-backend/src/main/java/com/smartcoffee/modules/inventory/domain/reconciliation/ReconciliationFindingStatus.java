package com.smartcoffee.modules.inventory.domain.reconciliation;

/**
 * Lifecycle of a reconciliation finding (Slice 2E). Constrained by
 * {@code ck_recon_finding_status} (V047).
 *
 * <p>A finding is a <b>report</b>, never an authority: repairing stock does not
 * happen by editing this row, it happens through the existing finalization
 * service and is merely <i>recorded</i> here.
 */
public enum ReconciliationFindingStatus {
    /** discovered, not yet acted on */
    OPEN,
    /** discovered and judged safe to auto-repair (a proposal, not yet run) */
    REPAIRABLE,
    /** a repair was applied through the guarded finalization path */
    REPAIRED,
    /** requires a human decision - never auto-repaired */
    MANUAL_REVIEW,
    /** a later run found the same subject no longer anomalous */
    SUPERSEDED,
    /** an operator acknowledged and closed it without a stock change */
    DISMISSED
}
