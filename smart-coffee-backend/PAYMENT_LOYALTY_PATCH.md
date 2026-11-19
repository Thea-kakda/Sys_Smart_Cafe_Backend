# Payment integration patch
After successful payment, call `promotionRedemptionService.redeemForOrder(orderId)`. Loyalty earning is recommended as an asynchronous `OrderCompleted` consumer using Consumer Inbox, not at Payment completion.
