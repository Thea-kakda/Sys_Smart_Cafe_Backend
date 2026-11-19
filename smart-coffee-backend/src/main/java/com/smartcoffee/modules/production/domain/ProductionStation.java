package com.smartcoffee.modules.production.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(schema = "production", name = "production_stations", uniqueConstraints = @UniqueConstraint(columnNames = {
        "store_id", "station_code" }))
public class ProductionStation {
    @Id
    @Column(name = "station_id")
    private UUID id;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "station_code", nullable = false)
    private String code;
    @Column(name = "station_name", nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "station_type", nullable = false)
    private StationType type;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(nullable = false)
    private boolean active;
    @Version
    private long version;

    protected ProductionStation() {
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public StationType type() {
        return type;
    }

    public boolean active() {
        return active;
    }

    public int displayOrder() {
        return displayOrder;
    }

    public long version() {
        return version;
    }
}

