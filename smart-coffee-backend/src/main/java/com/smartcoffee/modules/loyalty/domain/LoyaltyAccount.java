package com.smartcoffee.modules.loyalty.domain;

import com.smartcoffee.common.exception.ConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "loyalty", name = "loyalty_accounts")
public class LoyaltyAccount {
    @Id
    @Column(name = "loyalty_account_id")
    private UUID id;
    @Column(name = "customer_id", nullable = false, unique = true)
    private UUID customerId;
    @Column(name = "available_points", nullable = false)
    private long available;
    @Column(name = "reserved_points", nullable = false)
    private long reserved;
    @Column(name = "lifetime_earned_points", nullable = false)
    private long lifetimeEarned;
    @Column(name = "lifetime_redeemed_points", nullable = false)
    private long lifetimeRedeemed;
    @Column(nullable = false)
    private String status;
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected LoyaltyAccount() {
    }

    public static LoyaltyAccount create(UUID customer) {
        var a = new LoyaltyAccount();
        a.id = UUID.randomUUID();
        a.customerId = customer;
        a.status = "ACTIVE";
        a.joinedAt = Instant.now();
        a.updatedAt = a.joinedAt;
        return a;
    }

    public void earn(long p) {
        if (p <= 0)
            throw new IllegalArgumentException();
        available += p;
        lifetimeEarned += p;
        updatedAt = Instant.now();
    }

    public void reserve(long p) {
        if (p <= 0 || available < p)
            throw new ConflictException("LOYALTY_POINTS_INSUFFICIENT", "Insufficient points");
        available -= p;
        reserved += p;
        updatedAt = Instant.now();
    }

    public void redeem(long p) {
        if (p <= 0 || reserved < p)
            throw new ConflictException("LOYALTY_RESERVATION_CONFLICT", "Reserved points insufficient");
        reserved -= p;
        lifetimeRedeemed += p;
        updatedAt = Instant.now();
    }

    public void release(long p) {
        if (p <= 0 || reserved < p)
            throw new ConflictException("LOYALTY_RESERVATION_CONFLICT", "Reserved points insufficient");
        reserved -= p;
        available += p;
        updatedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public long available() {
        return available;
    }

    public long reserved() {
        return reserved;
    }

    public long lifetimeEarned() {
        return lifetimeEarned;
    }

    public long lifetimeRedeemed() {
        return lifetimeRedeemed;
    }

    public long version() {
        return version;
    }
}

