package com.smartcoffee.modules.customer.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "customer", name = "customer_profiles")
public class CustomerProfile {
    @Id @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;
    @Column(name = "preferred_store_id")
    private UUID preferredStoreId;
    @Column(name = "loyalty_number", unique = true, length = 40)
    private String loyaltyNumber;
    @Column(name = "birth_date")
    private LocalDate birthDate;
    @Column(name = "marketing_opt_in", nullable = false)
    private boolean marketingOptIn;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version @Column(nullable = false)
    private long version;

    protected CustomerProfile() {}
    private CustomerProfile(UUID userId) {
        this.id = UUID.randomUUID(); this.userId = userId; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public static CustomerProfile create(UUID userId) { return new CustomerProfile(userId); }
    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public UUID preferredStoreId() { return preferredStoreId; }
    public String loyaltyNumber() { return loyaltyNumber; }
    public LocalDate birthDate() { return birthDate; }
    public boolean marketingOptIn() { return marketingOptIn; }
    public void update(UUID storeId, LocalDate birthDate, boolean marketingOptIn) {
        this.preferredStoreId = storeId; this.birthDate = birthDate; this.marketingOptIn = marketingOptIn; this.updatedAt = Instant.now();
    }
}

