package com.smartcoffee.modules.rbac.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "user_role_assignments")
public class UserRoleAssignment {
    @Id
    @Column(name = "assignment_id")
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "role_id", nullable = false)
    private UUID roleId;
    @Column(name = "store_id")
    private UUID storeId;
    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    /** Who performed the grant/revocation (V052 provenance); null for seeded/bootstrap rows. */
    @Column(name = "assigned_by_user_id")
    private UUID assignedByUserId;
    @Column(name = "revoked_by_user_id")
    private UUID revokedByUserId;
    @Column(name = "revocation_reason")
    private String revocationReason;

    protected UserRoleAssignment() {
    }

    /**
     * Create a NEW, active role assignment. {@code storeId} null means a GLOBAL
     * grant (resolved by PermissionQueryRepository.global); a non-null storeId
     * scopes the role to that branch. Expiry/revocation start null. The actor id
     * is recorded for provenance (V052); bootstrap/seed rows pass null.
     */
    public static UserRoleAssignment assign(UUID userId, UUID roleId, UUID storeId, UUID assignedBy) {
        var a = new UserRoleAssignment();
        a.id = UUID.randomUUID();
        a.userId = userId;
        a.roleId = roleId;
        a.storeId = storeId;
        a.assignedAt = Instant.now();
        a.assignedByUserId = assignedBy;
        return a;
    }

    /** Backwards-compatible overload for system-originated grants (bootstrap). */
    public static UserRoleAssignment assign(UUID userId, UUID roleId, UUID storeId) {
        return assign(userId, roleId, storeId, null);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID roleId() {
        return roleId;
    }

    public UUID storeId() {
        return storeId;
    }

    public Instant assignedAt() {
        return assignedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public UUID assignedByUserId() {
        return assignedByUserId;
    }

    public UUID revokedByUserId() {
        return revokedByUserId;
    }

    public String revocationReason() {
        return revocationReason;
    }

    public boolean active() {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }

    /**
     * Set an expiry instant for a not-yet-revoked assignment (Master §6.6). The
     * caller must already have validated the value is in the future.
     */
    public void expireAt(Instant when) {
        this.expiresAt = when;
    }

    /**
     * Revoke with full provenance (V052). Callers must have passed the RBAC
     * policy and last-SUPER_ADMIN guard before reaching this.
     */
    public void revoke(UUID revokedBy, String reason) {
        this.revokedAt = Instant.now();
        this.revokedByUserId = revokedBy;
        this.revocationReason = reason;
    }
}

