package com.smartcoffee.modules.inventory.domain.reconciliation;

/**
 * Finding severity (Slice 2E). Ordered from least to most disruptive; the health
 * indicator maps CRITICAL findings to a DOWN signal. Persisted as
 * {@code inventory_reconciliation_findings.severity}, constrained by
 * {@code ck_recon_finding_severity} (V047).
 */
public enum ReconciliationSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}
