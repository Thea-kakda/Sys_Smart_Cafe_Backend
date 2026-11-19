package com.smartcoffee.common.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.springframework.data.annotation.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity extends UuidEntity {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    protected Instant createdAt;
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    protected Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

