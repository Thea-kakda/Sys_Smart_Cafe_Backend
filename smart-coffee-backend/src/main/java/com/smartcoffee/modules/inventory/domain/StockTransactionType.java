package com.smartcoffee.modules.inventory.domain;

public enum StockTransactionType {
    STOCK_IN,
    ADJUSTMENT,
    SALE_CONSUMPTION,
    RETURN,
    DAMAGED,
    PURCHASE_RECEIPT,
    TRANSFER_IN,
    TRANSFER_OUT,
    /** Reserved-balance move: stock is held for a cart line (Slice 2C). */
    SALE_RESERVATION,
    /** Reserved-balance move: held stock is given back (Slice 2C). */
    SALE_RELEASE,
    /** Production consumed raw material at completion (Slice 5A). Distinct from SALE_CONSUMPTION. */
    PRODUCTION_CONSUMPTION,
    /** Production posted finished-good output at completion (Slice 5A). */
    PRODUCTION_OUTPUT
}
