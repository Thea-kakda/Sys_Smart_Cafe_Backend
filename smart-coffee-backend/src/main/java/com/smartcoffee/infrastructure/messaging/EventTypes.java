package com.smartcoffee.infrastructure.messaging;

public final class EventTypes {
    private EventTypes() {
    }

    public static final String ORDER_PLACED = "OrderPlaced", PAYMENT_COMPLETED = "PaymentCompleted",
            INVENTORY_RESERVED = "InventoryReserved", ORDER_READY = "OrderReady",
            ORDER_CANCELLED = "OrderCancelled", ORDER_COMPLETED = "OrderCompleted",
            PROMOTION_RESERVED = "PromotionReserved", PROMOTION_REDEEMED = "PromotionRedeemed",
            PROMOTION_RELEASED = "PromotionReleased", PROMOTION_EXPIRED = "PromotionExpired";
}

