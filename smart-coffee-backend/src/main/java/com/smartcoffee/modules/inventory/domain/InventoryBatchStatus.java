package com.smartcoffee.modules.inventory.domain;

/**
 * Lifecycle of a tracked batch/lot (V038). An ACTIVE batch holds usable stock
 * and participates in FEFO selection. It is retired to EXHAUSTED when a
 * consumption draws it to zero, EXPIRED when it is written off as past its
 * expiry, or ADJUSTED_OUT for a manual waste/shortage write-off. No batch is
 * ever hard-deleted (A3) — retirement keeps the batch id referenced by ledger
 * lines valid.
 */
public enum InventoryBatchStatus {
    ACTIVE, EXHAUSTED, EXPIRED, ADJUSTED_OUT
}
