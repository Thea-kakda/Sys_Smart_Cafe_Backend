package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "inventory", name = "ingredients")
public class Ingredient {
    @Id
    @Column(name = "ingredient_id")
    private UUID id;
    @Column(name = "ingredient_code", nullable = false, unique = true, length = 50)
    private String code;
    @Column(name = "ingredient_name", nullable = false, length = 150)
    private String name;
    @Column(name = "base_unit_id", nullable = false)
    private UUID baseUnitId;
    @Column(name = "standard_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal standardCost;
    @Column(name = "track_inventory", nullable = false)
    private boolean trackInventory;
    @Column(nullable = false)
    private boolean active;
    // V036 master options consumed by M3 batch/FEFO and the ingredient editor.
    @Column(name = "track_batches", nullable = false)
    private boolean trackBatches;
    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Ingredient() {
    }

    public static Ingredient create(String code, String name, UUID baseUnitId, BigDecimal standardCost,
            boolean trackInventory, boolean trackBatches, Integer shelfLifeDays, boolean active) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Ingredient code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Ingredient name is required");
        }
        if (baseUnitId == null) {
            throw new IllegalArgumentException("Base unit is required");
        }
        if (standardCost == null || standardCost.signum() < 0) {
            throw new IllegalArgumentException("Standard cost must be zero or positive");
        }
        if (shelfLifeDays != null && shelfLifeDays <= 0) {
            throw new IllegalArgumentException("Shelf life must be a positive number of days when set");
        }
        var i = new Ingredient();
        i.id = UUID.randomUUID();
        i.code = code.trim();
        i.name = name.trim();
        i.baseUnitId = baseUnitId;
        i.standardCost = standardCost;
        i.trackInventory = trackInventory;
        i.trackBatches = trackBatches;
        i.shelfLifeDays = shelfLifeDays;
        i.active = active;
        var now = Instant.now();
        i.createdAt = now;
        i.updatedAt = now;
        return i;
    }

    /** Full replacement of the mutable descriptive fields (no hard delete — A3). */
    public void updateDetails(String code, String name, UUID baseUnitId, BigDecimal standardCost,
            boolean trackInventory, boolean trackBatches, Integer shelfLifeDays, boolean active) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Ingredient code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Ingredient name is required");
        }
        if (baseUnitId == null) {
            throw new IllegalArgumentException("Base unit is required");
        }
        if (standardCost == null || standardCost.signum() < 0) {
            throw new IllegalArgumentException("Standard cost must be zero or positive");
        }
        if (shelfLifeDays != null && shelfLifeDays <= 0) {
            throw new IllegalArgumentException("Shelf life must be a positive number of days when set");
        }
        this.code = code.trim();
        this.name = name.trim();
        this.baseUnitId = baseUnitId;
        this.standardCost = standardCost;
        this.trackInventory = trackInventory;
        this.trackBatches = trackBatches;
        this.shelfLifeDays = shelfLifeDays;
        this.active = active;
        this.updatedAt = Instant.now();
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

    public UUID baseUnitId() {
        return baseUnitId;
    }

    public BigDecimal standardCost() {
        return standardCost;
    }

    public boolean active() {
        return active;
    }

    public boolean trackInventory() {
        return trackInventory;
    }

    public boolean trackBatches() {
        return trackBatches;
    }

    public Integer shelfLifeDays() {
        return shelfLifeDays;
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
