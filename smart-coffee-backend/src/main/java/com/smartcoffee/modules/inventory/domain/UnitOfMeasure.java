package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.math.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "inventory", name = "units_of_measure")
public class UnitOfMeasure {
    @Id
    @Column(name = "unit_id")
    private UUID id;
    @Column(name = "unit_code", nullable = false, unique = true, length = 20)
    private String code;
    @Column(name = "unit_name", nullable = false, length = 60)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false, length = 20)
    private UnitType type;
    @Column(name = "decimal_scale", nullable = false)
    private int decimalScale;
    @Column(name = "conversion_factor_to_base", nullable = false, precision = 20, scale = 8)
    private BigDecimal conversionFactorToBase;
    @Column(name = "base_unit", nullable = false)
    private boolean baseUnit;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected UnitOfMeasure() {
    }

    public static UnitOfMeasure create(String code, String name, UnitType type, int decimalScale,
            BigDecimal conversionFactorToBase, boolean baseUnit, boolean active) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Unit code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Unit name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("Unit type is required");
        }
        validateScaleAndFactor(decimalScale, conversionFactorToBase);
        var u = new UnitOfMeasure();
        u.id = UUID.randomUUID();
        u.code = code.trim();
        u.name = name.trim();
        u.type = type;
        u.decimalScale = decimalScale;
        u.conversionFactorToBase = conversionFactorToBase;
        u.baseUnit = baseUnit;
        u.active = active;
        var now = Instant.now();
        u.createdAt = now;
        u.updatedAt = now;
        return u;
    }

    /** Full replacement of the mutable descriptive fields (no hard delete — A3). */
    public void update(String code, String name, UnitType type, int decimalScale, BigDecimal conversionFactorToBase,
            boolean baseUnit, boolean active) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Unit code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Unit name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("Unit type is required");
        }
        validateScaleAndFactor(decimalScale, conversionFactorToBase);
        this.code = code.trim();
        this.name = name.trim();
        this.type = type;
        this.decimalScale = decimalScale;
        this.conversionFactorToBase = conversionFactorToBase;
        this.baseUnit = baseUnit;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    private static void validateScaleAndFactor(int decimalScale, BigDecimal conversionFactorToBase) {
        if (decimalScale < 0 || decimalScale > 6) {
            throw new IllegalArgumentException("Decimal scale must be between 0 and 6");
        }
        if (conversionFactorToBase == null || conversionFactorToBase.signum() <= 0) {
            throw new IllegalArgumentException("Conversion factor must be positive");
        }
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

    public UnitType type() {
        return type;
    }

    public int decimalScale() {
        return decimalScale;
    }

    public BigDecimal factor() {
        return conversionFactorToBase;
    }

    public boolean baseUnit() {
        return baseUnit;
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

    public BigDecimal toBase(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0)
            throw new IllegalArgumentException("Quantity must be positive");
        return quantity.multiply(conversionFactorToBase).setScale(decimalScale, RoundingMode.HALF_UP);
    }

    public BigDecimal fromBase(BigDecimal quantity) {
        return quantity.divide(conversionFactorToBase, decimalScale, RoundingMode.HALF_UP);
    }
}

