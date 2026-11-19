package com.smartcoffee.modules.production.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.ordering.persistence.OrderRepository;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaProductionOrderAdapter implements ProductionOrderPort {
    private final OrderRepository orders;

    public JpaProductionOrderAdapter(OrderRepository o) {
        orders = o;
    }

    @Transactional(readOnly = true)
    public OrderProductionView load(UUID id) {
        var o = orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return new OrderProductionView(o.id(), o.storeId(), o.number(), o.status().name(),
                o.items().stream()
                        .map(i -> new ItemView(i.id(), i.productId(), i.productName(), i.quantity(), i.stationCode(),
                                i.customizations().stream().map(x -> x.groupName() + ": " + x.optionName()).toList()))
                        .toList());
    }

    @Transactional
    public void markPreparing(UUID id, UUID actor) {
        orders.lock(id).orElseThrow(() -> new ResourceNotFoundException("Order not found")).productionPreparing(actor);
    }

    @Transactional
    public void markReady(UUID id, UUID actor) {
        orders.lock(id).orElseThrow(() -> new ResourceNotFoundException("Order not found")).productionReady(actor);
    }
}

