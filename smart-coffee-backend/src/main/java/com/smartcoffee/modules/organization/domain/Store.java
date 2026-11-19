package com.smartcoffee.modules.organization.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "organization", name = "stores")
public class Store {
    @Id @Column(name = "store_id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "store_code", nullable = false, unique = true, length = 30)
    private String code;
    @Column(name = "store_name", nullable = false, length = 150)
    private String name;
    @Column(nullable = false, columnDefinition = "text")
    private String address;
    @Column(length = 30)
    private String phone;
    @Column(nullable = false, length = 64)
    private String timezone;
    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;
    @Column(name = "default_language", nullable = false, length = 10)
    private String defaultLanguage;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version @Column(nullable = false)
    private long version;

    protected Store() {}

    /**
     * Creates a new branch aggregate. The identifier is generated here because
     * organization.stores uses an application-assigned UUID primary key (no
     * database sequence/identity). {@code version} is left to JPA's default (0)
     * so a create never trusts a client-supplied version, while updates require
     * and validate the current version (see StoreManagementService).
     */
    public static Store create(String code, String name, String address, String phone, String timezone,
            String defaultCurrency, String defaultLanguage, boolean active) {
        Store s = new Store();
        s.id = UUID.randomUUID();
        s.code = code.trim();
        s.name = name.trim();
        s.address = address;
        s.phone = phone;
        s.timezone = timezone;
        s.defaultCurrency = defaultCurrency;
        s.defaultLanguage = defaultLanguage;
        s.active = active;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }

    /**
     * Replaces the mutable profile fields. {@code storeCode} is the stable
     * business identity and is intentionally never changed here. JPA increments
     * {@code version} on flush; the service validates the caller's expected
     * version before invoking this.
     */
    public void updateProfile(String name, String address, String phone, String timezone, String defaultCurrency,
            String defaultLanguage) {
        this.name = name.trim();
        this.address = address;
        this.phone = phone;
        this.timezone = timezone;
        this.defaultCurrency = defaultCurrency;
        this.defaultLanguage = defaultLanguage;
        this.updatedAt = Instant.now();
    }

    public void changeStatus(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String address() { return address; }
    public String phone() { return phone; }
    public String timezone() { return timezone; }
    public String defaultCurrency() { return defaultCurrency; }
    public String defaultLanguage() { return defaultLanguage; }
    public boolean active() { return active; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}

