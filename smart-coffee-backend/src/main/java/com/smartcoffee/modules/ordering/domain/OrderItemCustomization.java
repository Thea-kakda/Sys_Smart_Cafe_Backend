package com.smartcoffee.modules.ordering.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "ordering", name = "order_item_customizations")
public class OrderItemCustomization {
    @Id
    @Column(name = "order_item_customization_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;
    @Column(name = "group_id")
    private UUID groupId;
    @Column(name = "option_id")
    private UUID optionId;
    @Column(name = "group_name_snapshot", nullable = false)
    private String groupName;
    @Column(name = "option_name_snapshot", nullable = false)
    private String optionName;
    @Column(name = "additional_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal additionalPrice;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderItemCustomization() {
    }

    public OrderItemCustomization(UUID group, UUID option, String groupName, String optionName, BigDecimal price,
            int qty) {
        id = UUID.randomUUID();
        groupId = group;
        optionId = option;
        this.groupName = groupName;
        this.optionName = optionName;
        additionalPrice = price;
        quantity = qty;
        createdAt = Instant.now();
    }

    void attach(OrderItem i) {
        orderItem = i;
    }

    public UUID id() {
        return id;
    }

    public UUID orderItemId() {
        return orderItem == null ? null : orderItem.id();
    }

    public String groupName() {
        return groupName;
    }

    public String optionName() {
        return optionName;
    }

    public BigDecimal additionalPrice() {
        return additionalPrice;
    }

    public int quantity() {
        return quantity;
    }
}

