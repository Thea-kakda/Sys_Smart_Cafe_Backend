package com.smartcoffee.modules.rbac.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "permissions")
public class Permission {
    @Id
    @Column(name = "permission_id")
    private UUID id;
    @Column(name = "permission_code", nullable = false, unique = true)
    private String code;

    protected Permission() {
    }

    public String code() {
        return code;
    }

    public UUID id() {
        return id;
    }
}

