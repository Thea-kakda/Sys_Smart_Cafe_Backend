package com.smartcoffee.modules.rbac.domain;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * A role in {@code identity.roles}. Since V052 a role carries its lifecycle
 * metadata: {@code isSystem} marks the eight migration-seeded roles as
 * immutable (no rename, no delete — enforced by RoleManagementService),
 * {@code description} is free text, and {@code roleScope} pins whether the
 * role may only be assigned globally (store_id NULL) or only per store.
 */
@Entity
@Table(schema = "identity", name = "roles")
public class Role {
    @Id
    @Column(name = "role_id")
    private UUID id;
    @Column(name = "role_code", nullable = false, unique = true)
    private String code;
    @Column(name = "role_name", nullable = false)
    private String name;
    @Column(name = "is_system", nullable = false)
    private boolean isSystem;
    @Column(name = "description")
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "role_scope", nullable = false)
    private RoleScope roleScope;

    protected Role() {
    }

    /** A custom (non-system) role minted through POST /management/roles. */
    public static Role createCustom(String code, String name, String description, RoleScope scope) {
        Role r = new Role();
        r.id = UUID.randomUUID();
        r.code = code;
        r.name = name;
        r.description = description;
        r.roleScope = scope;
        r.isSystem = false;
        return r;
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

    public boolean isSystem() {
        return isSystem;
    }

    public String description() {
        return description;
    }

    public RoleScope roleScope() {
        return roleScope;
    }

    /** Only role_name and description are mutable; code/scope/system are not. */
    public void rename(String newName, String newDescription) {
        this.name = newName;
        this.description = newDescription;
    }
}

