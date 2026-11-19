package com.smartcoffee.modules.ordering.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.ordering.api.*;
import com.smartcoffee.modules.ordering.domain.Order;
import com.smartcoffee.modules.ordering.domain.OrderItem;
import com.smartcoffee.modules.ordering.domain.OrderItemCustomization;
import com.smartcoffee.modules.ordering.persistence.OrderItemCustomizationRepository;
import com.smartcoffee.modules.ordering.persistence.OrderRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {
    private final OrderRepository orders;
    private final OrderItemCustomizationRepository customizations;
    private final OrderMapper mapper;

    public OrderQueryService(OrderRepository o, OrderItemCustomizationRepository c, OrderMapper m) {
        orders = o;
        customizations = c;
        mapper = m;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID customer, UUID id) {
        Order order = orders.findByIdAndCustomerId(id, customer)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return mapper.response(order, false, customizationsByItem(List.of(order)));
    }

    /**
     * Customer order history (Full-stack wiring §1.2): the authenticated
     * customer's OWN orders, newest first, bounded page. Ownership is the
     * resolver-supplied customer id in the query itself — there is no id
     * parameter to forge, so cross-customer reads are structurally impossible.
     * Items are included via the repository entity graph (what the customer
     * ordered is their own data); customizations for the whole page load in one
     * batch keyed by line id.
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> list(UUID customer, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Page<Order> result = orders.findByCustomerIdOrderByOrderedAtDesc(customer,
                PageRequest.of(safePage, safeSize));
        Map<UUID, List<OrderItemCustomization>> byItem = customizationsByItem(result.getContent());
        return result.map(o -> mapper.response(o, false, byItem));
    }

    private Map<UUID, List<OrderItemCustomization>> customizationsByItem(List<Order> orders) {
        List<UUID> itemIds = orders.stream()
                .flatMap(o -> o.items().stream())
                .map(OrderItem::id)
                .toList();
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return customizations.findAllForItems(itemIds).stream()
                .collect(Collectors.groupingBy(OrderItemCustomization::orderItemId, LinkedHashMap::new,
                        Collectors.toList()));
    }
}
