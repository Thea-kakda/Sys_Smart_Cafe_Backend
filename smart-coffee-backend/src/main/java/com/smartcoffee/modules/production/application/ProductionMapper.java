package com.smartcoffee.modules.production.application;

import com.smartcoffee.modules.production.api.*;
import com.smartcoffee.modules.production.domain.*;
import org.springframework.stereotype.Component;

@Component
public class ProductionMapper {
    public ProductionTicketResponse response(ProductionTicket t, ProductionStation s) {
        return new ProductionTicketResponse(t.id(), t.number(), t.orderId(), t.storeId(), t.stationId(),
                s.type().name(), t.status().name(), t.priority().name(), t.queuedAt(),
                t.items().stream()
                        .map(i -> new ProductionItemResponse(i.id(), i.orderItemId(), i.productName(), i.quantity(),
                                i.customizationSummary(), i.status().name(), i.startedAt(), i.readyAt(), i.version()))
                        .toList(),
                t.version());
    }
}

