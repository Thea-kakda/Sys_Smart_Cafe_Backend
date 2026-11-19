package com.smartcoffee.modules.inventory.domain.reconciliation;

/**
 * Lifecycle of a reconciliation run (Slice 2E). Constrained by
 * {@code ck_recon_run_status} (V047).
 */
public enum ReconciliationRunStatus {
    /** the run is scanning; no counters are trustworthy yet */
    RUNNING,
    /** completed and the scan found nothing worth reporting */
    COMPLETED,
    /** completed with at least one finding recorded */
    COMPLETED_WITH_FINDINGS,
    /** aborted (transient failure after retries, or an operator error) */
    FAILED
}
