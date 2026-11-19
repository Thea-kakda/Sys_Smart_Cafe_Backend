package com.smartcoffee.modules.identity.persistence;

import com.smartcoffee.modules.identity.domain.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read model for Staff Administration (Master Prompt §6). Deliberately
 * read-only and native: staff listing must join identity.users with
 * identity.user_role_assignments / roles and organization.stores, which spans
 * module aggregates. Reads that cross aggregates are done here as SQL; every
 * WRITE (create user, assign/revoke role) still goes through the owning JPA
 * repositories so module ownership boundaries are preserved.
 *
 * <p>Filter parameters follow the same sentinel style the branch-management
 * search uses, to sidestep Hibernate 7 / pgjdbc null-parameter inference: the
 * text params ({@code search}, {@code statusValue}, {@code roleCode}) are never
 * null (empty string means "no filter") and {@code storeId} always carries a
 * valid UUID literal, gated by its {@code storeIdFilter} flag so an unused
 * predicate can never fail a {@code cast(... as uuid)}.
 */
public interface StaffQueryRepository extends Repository<User, UUID> {

    @Query(value = """
            select u.user_id as userId, u.full_name as fullName, u.email as email, u.phone as phone,
                   u.preferred_language as preferredLanguage, u.status as status,
                   u.created_at as createdAt, u.updated_at as updatedAt, u.version as version
            from identity.users u
            where (:search = '' or lower(u.full_name) like lower(concat('%', :search, '%'))
                   or lower(u.email) like lower(concat('%', :search, '%')))
              and (:statusFilter = false or u.status = :statusValue)
              and (:roleCodeFilter = false or exists (
                    select 1 from identity.user_role_assignments a1
                    join identity.roles r1 on r1.role_id = a1.role_id
                    where a1.user_id = u.user_id and a1.revoked_at is null
                          and (a1.expires_at is null or a1.expires_at > :now) and r1.role_code = :roleCode))
              and (:storeIdFilter = false or exists (
                    select 1 from identity.user_role_assignments a2
                    where a2.user_id = u.user_id and a2.store_id = cast(:storeId as uuid)
                          and a2.revoked_at is null and (a2.expires_at is null or a2.expires_at > :now)))
            """, countQuery = """
            select count(*) from identity.users u
            where (:search = '' or lower(u.full_name) like lower(concat('%', :search, '%'))
                   or lower(u.email) like lower(concat('%', :search, '%')))
              and (:statusFilter = false or u.status = :statusValue)
              and (:roleCodeFilter = false or exists (
                    select 1 from identity.user_role_assignments a1
                    join identity.roles r1 on r1.role_id = a1.role_id
                    where a1.user_id = u.user_id and a1.revoked_at is null
                          and (a1.expires_at is null or a1.expires_at > :now) and r1.role_code = :roleCode))
              and (:storeIdFilter = false or exists (
                    select 1 from identity.user_role_assignments a2
                    where a2.user_id = u.user_id and a2.store_id = cast(:storeId as uuid)
                          and a2.revoked_at is null and (a2.expires_at is null or a2.expires_at > :now)))
            """, nativeQuery = true)
    Page<StaffUserProjection> searchUsers(@Param("search") String search,
            @Param("statusFilter") boolean statusFilter, @Param("statusValue") String statusValue,
            @Param("roleCodeFilter") boolean roleCodeFilter, @Param("roleCode") String roleCode,
            @Param("storeIdFilter") boolean storeIdFilter, @Param("storeId") String storeId,
            @Param("now") Instant now, Pageable pageable);

    @Query(value = """
            select u.user_id as userId, u.full_name as fullName, u.email as email, u.phone as phone,
                   u.preferred_language as preferredLanguage, u.status as status,
                   u.created_at as createdAt, u.updated_at as updatedAt, u.version as version
            from identity.users u where u.user_id = cast(:userId as uuid)
            """, nativeQuery = true)
    StaffUserProjection findUser(@Param("userId") UUID userId);

    /** Active + historical assignments for the given users, with role and store labels. */
    @Query(value = """
            select a.user_id as userId, a.assignment_id as assignmentId, r.role_code as roleCode,
                   r.role_name as roleName, a.store_id as storeId, s.store_name as storeName,
                   a.assigned_at as assignedAt, a.expires_at as expiresAt, a.revoked_at as revokedAt
            from identity.user_role_assignments a
            join identity.roles r on r.role_id = a.role_id
            left join organization.stores s on s.store_id = a.store_id
            where a.user_id in (:userIds)
            order by a.assigned_at desc
            """, nativeQuery = true)
    List<StaffAssignmentProjection> assignmentsByUserIds(@Param("userIds") Collection<UUID> userIds);

    /**
     * Paginated assignments of one role (active AND historical) with the holder's
     * account status — V052 role:assignment-read surface. Ids/status/scope only:
     * no email or phone, so the read capability cannot become a PII side channel.
     */
    @Query(value = """
            select a.assignment_id as assignmentId, a.user_id as userId, u.status as userStatus,
                   a.store_id as storeId, a.assigned_at as assignedAt, a.expires_at as expiresAt,
                   a.revoked_at as revokedAt
            from identity.user_role_assignments a
            join identity.users u on u.user_id = a.user_id
            where a.role_id = cast(:roleId as uuid)
            order by a.assigned_at desc
            """, countQuery = """
            select count(*) from identity.user_role_assignments a
            where a.role_id = cast(:roleId as uuid)
            """, nativeQuery = true)
    Page<RoleAssignmentRow> assignmentsByRole(@Param("roleId") UUID roleId, Pageable pageable);

    /** True when the branch exists; read-only existence check for create/assign validation. */
    @Query(value = "select exists(select 1 from organization.stores s where s.store_id = cast(:storeId as uuid))",
            nativeQuery = true)
    boolean storeExists(@Param("storeId") UUID storeId);

    /**
     * Number of currently-usable global administrators: ACTIVE users holding a
     * non-revoked, non-expired store-NULL SUPER_ADMIN/ADMIN assignment. Used to
     * prevent disabling the final administrator (Master §6.5).
     */
    @Query(value = """
            select count(distinct u.user_id) from identity.users u
            join identity.user_role_assignments a on a.user_id = u.user_id
            join identity.roles r on r.role_id = a.role_id
            where u.status = 'ACTIVE' and a.store_id is null and a.revoked_at is null
                  and (a.expires_at is null or a.expires_at > now())
                  and r.role_code in ('SUPER_ADMIN', 'ADMIN')
            """, nativeQuery = true)
    int countActiveGlobalAdmins();

    /** Read-only projection over identity.users for the staff list/detail header. */
    interface StaffUserProjection {
        UUID getUserId();

        String getFullName();

        String getEmail();

        String getPhone();

        String getPreferredLanguage();

        String getStatus();

        Instant getCreatedAt();

        Instant getUpdatedAt();

        long getVersion();
    }

    /** Read-only projection over one role assignment, with its role and store labels. */
    interface StaffAssignmentProjection {
        UUID getUserId();

        UUID getAssignmentId();

        String getRoleCode();

        String getRoleName();

        UUID getStoreId();

        String getStoreName();

        Instant getAssignedAt();

        Instant getExpiresAt();

        Instant getRevokedAt();
    }

    /** Read-only projection for the V052 per-role assignment listing. */
    interface RoleAssignmentRow {
        UUID getAssignmentId();

        UUID getUserId();

        String getUserStatus();

        UUID getStoreId();

        Instant getAssignedAt();

        Instant getExpiresAt();

        Instant getRevokedAt();
    }
}
