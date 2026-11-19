package com.smartcoffee.modules.production.domain;

/**
 * Reason a production-order waste line was recorded (Slice 5A §3, spec table
 * on Waste fields). Stored as a varchar on
 * {@code production.production_order_waste.reason_code} and constrained by
 * {@code ck_production_order_waste_reason} (V051).
 *
 * <p>Phase 5A.1 stores the row shape but does not post a stock movement;
 * Phase 5A.2 wires the DAMAGED ledger transaction with this code as the
 * reason metadata.
 */
public enum ProductionOrderWasteReason {
    SPOILED,
    BURNT,
    DROPPED,
    QUALITY_FAIL,
    OVER_PRODUCED,
    OTHER
}
