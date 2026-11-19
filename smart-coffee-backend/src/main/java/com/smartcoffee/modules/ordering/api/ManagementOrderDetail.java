package com.smartcoffee.modules.ordering.api;

import java.util.List;

/**
 * Administrative order detail (Master Prompt §8): the {@link ManagementOrderSummary}
 * header plus line items. Offered because the existing customer detail endpoint
 * ({@code GET /api/v1/orders/{id}}) is scoped to the authenticated customer and does
 * not enforce management (store-based) authorization, so administrators need a
 * separately-authorized read. It still omits customer personal data.
 */
public record ManagementOrderDetail(
        ManagementOrderSummary order,
        List<OrderItemResponse> items,
        ManagementPromotionSnapshot promotion) {
}
