package com.smartcoffee.modules.rbac.persistence;

import com.smartcoffee.modules.rbac.domain.Role;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Data access for Roles &amp; Permissions administration (Master Prompt §7). Roles,
 * permissions and the role_permissions join table are RBAC-owned, so both the read
 * model and the grant replacement live here and never leak into another module.
 *
 * <p>Reads are native projections because {@code identity.role_permissions} has no
 * JPA entity (composite primary key, no version/timestamp columns) and the listing
 * must join roles → role_permissions → permissions. Writes are the narrow, audited
 * operations the admin service needs: delete-all-then-insert for one role's grants.
 * The {@code cast(:x as uuid)} guards follow the sentinel conventions already used
 * by {@code StaffQueryRepository} to avoid pgjdbc null-parameter type inference.
 */
public interface RolePermissionManagementRepository extends Repository<Role, UUID> {

    // ------------------------------------------------------------------ reads

    @Query(value = """
            select r.role_id as roleId, r.role_code as roleCode, r.role_name as roleName,
                   r.is_system as isSystem, r.role_scope as roleScope, r.description as description
            from identity.roles r order by r.role_code
            """, nativeQuery = true)
    List<RoleProjection> roles();

    @Query(value = """
            select r.role_id as roleId, r.role_code as roleCode, r.role_name as roleName,
                   r.is_system as isSystem, r.role_scope as roleScope, r.description as description
            from identity.roles r where r.role_id = cast(:roleId as uuid)
            """, nativeQuery = true)
    RoleProjection findRole(@Param("roleId") UUID roleId);

    @Query(value = """
            select p.permission_id as permissionId, p.permission_code as permissionCode
            from identity.permissions p order by p.permission_code
            """, nativeQuery = true)
    List<PermissionProjection> permissions();

    /** Permission codes currently granted to one role. */
    @Query(value = """
            select p.permission_code as permissionCode
            from identity.role_permissions rp
            join identity.permissions p on p.permission_id = rp.permission_id
            where rp.role_id = cast(:roleId as uuid)
            order by p.permission_code
            """, nativeQuery = true)
    List<CodeProjection> grantedCodes(@Param("roleId") UUID roleId);

    /** Every (role → permission code) grant, so the list view groups without N+1. */
    @Query(value = """
            select rp.role_id as roleId, p.permission_code as permissionCode
            from identity.role_permissions rp
            join identity.permissions p on p.permission_id = rp.permission_id
            order by p.permission_code
            """, nativeQuery = true)
    List<RoleGrantProjection> allGrants();

    /**
     * Resolve a set of requested permission codes to (id, code), returning ONLY the
     * ones that exist; the service compares against the request to reject unknown
     * codes without ever creating a permission through this path.
     */
    @Query(value = """
            select p.permission_id as permissionId, p.permission_code as permissionCode
            from identity.permissions p where p.permission_code in (:codes)
            """, nativeQuery = true)
    List<PermissionProjection> findByCodes(@Param("codes") Collection<String> codes);

    // ----------------------------------------------------------------- writes

    /** Remove all grants for a role (part of a transactional replacement). */
    @Modifying
    @Query(value = "delete from identity.role_permissions where role_id = cast(:roleId as uuid)", nativeQuery = true)
    void deleteGrants(@Param("roleId") UUID roleId);

    /** Insert one role→permission grant; idempotent to survive re-application. */
    @Modifying
    @Query(value = """
            insert into identity.role_permissions (role_id, permission_id)
            values (cast(:roleId as uuid), cast(:permissionId as uuid))
            on conflict (role_id, permission_id) do nothing
            """, nativeQuery = true)
    void insertGrant(@Param("roleId") UUID roleId, @Param("permissionId") UUID permissionId);

    // ------------------------------------------------------------- projections

    /**
     * V052 invariant check (D4): permission codes with NO SUPER_ADMIN grant.
     * Expected empty at all times; the startup check logs ERROR when non-empty,
     * and the CI invariant test fails on any row.
     */
    @Query(value = """
            select p.permission_code
            from identity.permissions p
            where not exists (
                select 1 from identity.role_permissions rp
                join identity.roles r on r.role_id = rp.role_id
                where r.role_code = 'SUPER_ADMIN' and rp.permission_id = p.permission_id)
            order by p.permission_code
            """, nativeQuery = true)
    List<String> missingSuperAdminCodes();

    interface RoleProjection {
        UUID getRoleId();

        String getRoleCode();

        String getRoleName();

        boolean getIsSystem();

        String getRoleScope();

        String getDescription();
    }

    interface PermissionProjection {
        UUID getPermissionId();

        String getPermissionCode();
    }

    interface CodeProjection {
        String getPermissionCode();
    }

    interface RoleGrantProjection {
        UUID getRoleId();

        String getPermissionCode();
    }
}
