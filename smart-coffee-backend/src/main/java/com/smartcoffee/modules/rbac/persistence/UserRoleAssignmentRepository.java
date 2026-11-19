package com.smartcoffee.modules.rbac.persistence;

import com.smartcoffee.modules.rbac.domain.UserRoleAssignment;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Write-side repository for role assignments. Reads for authorization remain
 * on {@link PermissionQueryRepository}; this exists so application code
 * (staff provisioning, super-admin bootstrap) can create assignments instead
 * of relying on seed SQL alone.
 */
public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignment, UUID> {
    /** An active, non-expired GLOBAL assignment (store_id IS NULL) for the user/role pair. */
    @Query("select a from UserRoleAssignment a where a.userId=:userId and a.roleId=:roleId "
            + "and a.storeId is null and a.revokedAt is null "
            + "and (a.expiresAt is null or a.expiresAt>:now)")
    Optional<UserRoleAssignment> findActiveGlobal(@Param("userId") UUID userId, @Param("roleId") UUID roleId,
            @Param("now") Instant now);

    /** All assignments (active, expired or revoked) for a user/role pair; duplicate-active checks filter in service. */
    List<UserRoleAssignment> findByUserIdAndRoleId(UUID userId, UUID roleId);

    /** Every assignment held by a user, for the staff detail view. */
    List<UserRoleAssignment> findByUserId(UUID userId);

    /**
     * Distinct ACTIVE users (non-revoked, non-expired) holding the given role,
     * used to invalidate their security_version after the role's permission set
     * changes (Master §7.2). Read-only projection of the rbac-owned table.
     */
    @Query(value = "select distinct a.user_id from identity.user_role_assignments a "
            + "where a.role_id = cast(:roleId as uuid) and a.revoked_at is null "
            + "and (a.expires_at is null or a.expires_at > now())", nativeQuery = true)
    List<UUID> activeUserIdsByRole(@Param("roleId") UUID roleId);

    /**
     * Active GLOBAL holders of a role whose user is still ACTIVE — the exact
     * definition the last-SUPER_ADMIN guard counts (V052). Evaluated AFTER the
     * mutating write inside the same transaction, under the role row lock.
     */
    @Query(value = "select count(*) from identity.user_role_assignments a "
            + "join identity.users u on u.user_id = a.user_id "
            + "where a.role_id = cast(:roleId as uuid) and a.store_id is null "
            + "and a.revoked_at is null and (a.expires_at is null or a.expires_at > :now) "
            + "and u.status = 'ACTIVE'", nativeQuery = true)
    long countActiveGlobalHolders(@Param("roleId") UUID roleId, @Param("now") Instant now);

    /** Role is referenced by ANY assignment, active or historical (delete guard). */
    boolean existsByRoleId(UUID roleId);

    /** Paginated assignments of one role for GET /management/roles/{roleId}/assignments. */
    Page<UserRoleAssignment> findByRoleId(UUID roleId, Pageable pageable);
}
