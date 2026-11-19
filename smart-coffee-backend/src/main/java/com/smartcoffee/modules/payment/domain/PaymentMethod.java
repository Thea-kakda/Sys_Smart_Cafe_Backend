package com.smartcoffee.modules.payment.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(schema = "payment", name = "payment_methods")
public class PaymentMethod {
    @Id
    @Column(name = "payment_method_id")
    private UUID id;
    @Column(name = "method_code", nullable = false, unique = true)
    private String code;
    @Column(name = "method_name", nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "method_type", nullable = false)
    private PaymentMethodType type;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Version
    private long version;

    protected PaymentMethod() {
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public PaymentMethodType type() {
        return type;
    }

    public boolean active() {
        return active;
    }
}

