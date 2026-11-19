package com.smartcoffee.common.persistence;

import jakarta.persistence.*;
import java.util.UUID;

@MappedSuperclass
public abstract class UuidEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    protected UUID id;
    @Version
    @Column(nullable = false)
    protected long version;

    protected UuidEntity() {
        this.id = UUID.randomUUID();
    }

    public UUID getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }
}

