package com.smartcoffee.modules.inventory.domain;

/**
 * Inter-branch transfer lifecycle (V035 ck_transfer_status). Forward-only
 * except for cancellation before receipt; transitions are enforced in
 * {@link StockTransfer}: REQUESTED -> IN_TRANSIT (dispatch) -> RECEIVED, and
 * REQUESTED/IN_TRANSIT -> CANCELLED.
 */
public enum StockTransferStatus {
    REQUESTED, IN_TRANSIT, RECEIVED, CANCELLED
}
