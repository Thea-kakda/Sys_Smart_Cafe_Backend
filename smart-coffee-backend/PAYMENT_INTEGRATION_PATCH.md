# Required Payment integration
After successful Payment and Order confirmation, call `ProductionTicketService.createForOrder(orderId)` in the same database transaction. Do this once only. Database uniqueness `(order_id, station_id)` prevents duplicate station tickets.
