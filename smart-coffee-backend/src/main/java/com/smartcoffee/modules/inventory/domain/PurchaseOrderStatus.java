package com.smartcoffee.modules.inventory.domain;

/**
 * Purchase-order lifecycle (V033 ck_po_status). Forward-only except for the
 * cancellable pre-receipt states; the transitions are enforced in
 * {@link PurchaseOrder}.
 */
public enum PurchaseOrderStatus {
    DRAFT, SUBMITTED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED, CLOSED
}
