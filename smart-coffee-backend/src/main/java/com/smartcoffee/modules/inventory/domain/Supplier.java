package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Supplier master data for the purchasing flow (V033). Soft-deactivated only
 * (A3: no hard deletes) — {@code active=false} hides it from new purchase
 * orders while preserving historical references. Optimistic locking via
 * {@code @Version}; the id is application-generated.
 */
@Entity
@Table(schema = "inventory", name = "suppliers")
public class Supplier {
    @Id
    @Column(name = "supplier_id")
    private UUID id;
    @Column(name = "supplier_code", nullable = false, unique = true, length = 50)
    private String code;
    @Column(name = "supplier_name", nullable = false, length = 150)
    private String name;
    @Column(name = "contact_name", length = 150)
    private String contactName;
    @Column(length = 30)
    private String phone;
    @Column(length = 150)
    private String email;
    @Column(columnDefinition = "text")
    private String address;
    @Column(name = "tax_id", length = 50)
    private String taxId;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Supplier() {
    }

    public static Supplier create(String code, String name, String contactName, String phone, String email,
            String address, String taxId, boolean active) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Supplier code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Supplier name is required");
        }
        var s = new Supplier();
        s.id = UUID.randomUUID();
        s.code = code.trim();
        s.name = name.trim();
        s.contactName = blankToNull(contactName);
        s.phone = blankToNull(phone);
        s.email = blankToNull(email);
        s.address = blankToNull(address);
        s.taxId = blankToNull(taxId);
        s.active = active;
        var now = Instant.now();
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    /** Full replacement of the mutable descriptive fields (image-free master). */
    public void updateDetails(String code, String name, String contactName, String phone, String email,
            String address, String taxId, boolean active) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Supplier code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Supplier name is required");
        }
        this.code = code.trim();
        this.name = name.trim();
        this.contactName = blankToNull(contactName);
        this.phone = blankToNull(phone);
        this.email = blankToNull(email);
        this.address = blankToNull(address);
        this.taxId = blankToNull(taxId);
        this.active = active;
        this.updatedAt = Instant.now();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
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

    public String contactName() {
        return contactName;
    }

    public String phone() {
        return phone;
    }

    public String email() {
        return email;
    }

    public String address() {
        return address;
    }

    public String taxId() {
        return taxId;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
