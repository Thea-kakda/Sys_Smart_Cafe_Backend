package com.smartcoffee.modules.catalog.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "catalog", name = "store_products")
public class StoreProduct {
    @EmbeddedId
    private StoreProductId id;
    @Column(name = "station_code", length = 40)
    private String stationCode;
    @Column(name = "override_price", precision = 14, scale = 4)
    private BigDecimal overridePrice;
    @Column(nullable = false)
    private boolean available;
    @Column(name = "sold_out_until")
    private Instant soldOutUntil;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(nullable = false)
    private long version;

    protected StoreProduct() {
    }

    /**
     * First branch configuration row for a product (Checkpoint 3B). Station is
     * mandatory for newly configured rows so production routing never relies
     * on the legacy fallback; an explicit sold-out window is optional.
     */
    public static StoreProduct create(UUID storeId, UUID productId, String stationCode,
            BigDecimal overridePrice, boolean available, Instant soldOutUntil) {
        if (storeId == null || productId == null) {
            throw new IllegalArgumentException("Store and product id are required");
        }
        if (stationCode == null || stationCode.isBlank()) {
            throw new IllegalArgumentException("Station code is required");
        }
        if (overridePrice != null && overridePrice.signum() < 0) {
            throw new IllegalArgumentException("Override price must not be negative");
        }
        var storeProduct = new StoreProduct();
        storeProduct.id = new StoreProductId(storeId, productId);
        storeProduct.stationCode = stationCode.trim();
        storeProduct.overridePrice = overridePrice;
        storeProduct.available = available;
        storeProduct.soldOutUntil = soldOutUntil;
        storeProduct.updatedAt = Instant.now();
        return storeProduct;
    }

    /**
     * Full-replacement configuration update (Checkpoint 3B). Null
     * overridePrice means inherit the base price; null soldOutUntil clears a
     * temporary sold-out window. Required fields are never silently reset.
     */
    public void reconfigure(String stationCode, BigDecimal overridePrice, boolean available, Instant soldOutUntil) {
        if (stationCode == null || stationCode.isBlank()) {
            throw new IllegalArgumentException("Station code is required");
        }
        if (overridePrice != null && overridePrice.signum() < 0) {
            throw new IllegalArgumentException("Override price must not be negative");
        }
        this.stationCode = stationCode.trim();
        this.overridePrice = overridePrice;
        this.available = available;
        this.soldOutUntil = soldOutUntil;
        this.updatedAt = Instant.now();
    }

    public UUID storeId() {
        return id.storeId();
    }

    public UUID productId() {
        return id.productId();
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public String stationCode() {
        return stationCode;
    }

    public BigDecimal overridePrice() {
        return overridePrice;
    }

    public boolean available() {
        return available && (soldOutUntil == null || soldOutUntil.isBefore(Instant.now()));
    }

    public Instant soldOutUntil() {
        return soldOutUntil;
    }

    /**
     * Manual sold-out control (Slice 3B, spec §6). Writes the RAW standing
     * {@code available} lever and an optional temporary {@code soldOutUntil} window
     * without touching station or price. {@code available=false} is a standing
     * sold-out; {@code available=true} with a future {@code soldOutUntil} is a
     * temporary one; clearing both returns the item to AUTO. The effective boolean
     * exposed by {@link #available()} stays the single legacy source of truth, so the
     * Slice 3A availability policy continues to interpret it unchanged (no oversell).
     */
    public void applyManualSoldOut(boolean available, Instant soldOutUntil) {
        this.available = available;
        this.soldOutUntil = soldOutUntil;
        this.updatedAt = Instant.now();
    }
}

