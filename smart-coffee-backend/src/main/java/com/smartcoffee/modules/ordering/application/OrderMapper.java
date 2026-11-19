package com.smartcoffee.modules.ordering.application;

import com.smartcoffee.modules.ordering.api.*;
import com.smartcoffee.modules.ordering.domain.Order;
import com.smartcoffee.modules.ordering.domain.OrderItem;
import com.smartcoffee.modules.ordering.domain.OrderItemCustomization;
import com.smartcoffee.modules.ordering.domain.OrderPromotion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    /**
     * In-memory mapping (checkout, command responses): customizations are read
     * from the entity graph the service already holds in the persistence context.
     */
    public OrderResponse response(Order o, boolean replay) {
        Map<UUID, List<OrderItemCustomization>> customizations = new HashMap<>();
        for (OrderItem item : o.items()) {
            customizations.put(item.id(), item.customizations());
        }
        return response(o, replay, customizations);
    }

    /**
     * Two-stage read mapping: customizations were batch-loaded by
     * {@code order_item_id} and are keyed per line, so every order line is
     * rendered exactly once and no line can pick up another line's values.
     */
    public OrderResponse response(Order o, boolean replay,
            Map<UUID, List<OrderItemCustomization>> customizationsByItem) {
        OrderPromotion promotion = o.promotion();
        return new OrderResponse(o.id(), o.number(), o.status().name(), o.paymentStatus().name(), o.currency(),
                o.subtotal(), o.discount(), o.tax(), o.deliveryFee(), o.rounding(), o.total(), o.totalQuantity(),
                o.orderedAt(),
                o.items().stream()
                        .map(i -> item(i, customizationsByItem.getOrDefault(i.id(), List.of())))
                        .toList(),
                replay,
                promotion == null ? null : promotion.promotionId(), promotion == null ? null : promotion.code(),
                promotion == null ? null : promotion.name(), promotion == null ? null : promotion.type(),
                promotion == null ? null : promotion.value(),
                promotion == null ? null : promotion.eligibleSubtotal(),
                promotion == null ? null : promotion.discountAmount(),
                promotion == null ? null : promotion.version());
    }

    private static OrderItemResponse item(OrderItem i, List<OrderItemCustomization> customizations) {
        return new OrderItemResponse(i.id(), i.productId(), i.productName(), i.quantity(), i.lineSubtotal(),
                i.lineDiscount(), i.lineTaxableAmount(), i.lineTax(), i.lineTotal(),
                customizations.stream().map(x -> x.groupName() + ": " + x.optionName()).toList());
    }
}
