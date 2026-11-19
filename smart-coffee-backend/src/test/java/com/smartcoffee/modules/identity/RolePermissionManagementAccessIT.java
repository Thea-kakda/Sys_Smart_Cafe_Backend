package com.smartcoffee.modules.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.identity.api.CreateStaffRequest;
import com.smartcoffee.modules.identity.api.PermissionResponse;
import com.smartcoffee.modules.identity.api.RoleResponse;
import com.smartcoffee.modules.identity.api.StaffResponse;
import com.smartcoffee.modules.identity.application.RolePermissionManagementService;
import com.smartcoffee.modules.identity.application.StaffManagementService;
import com.smartcoffee.modules.organization.api.CreateStoreRequest;
import com.smartcoffee.modules.organization.application.StoreManagementService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Roles &amp; Permissions administration verification against a real migrated
 * database (Flyway through V024). Confirms V024 grants reach global admins only, the
 * list/detail reads reflect the seed, and the permission-set replacement enforces
 * transactional semantics, unknown-code rejection, SUPER_ADMIN baseline protection
 * and member security_version invalidation. Service-level, mirroring the other
 * management ITs. A scratch role is used for mutations so global seed data is never
 * clobbered in the shared Testcontainers database.
 */
class RolePermissionManagementAccessIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    StoreManagementService storeManagement;
    @Autowired
    StaffManagementService staff;
    @Autowired
    RolePermissionManagementService roles;

    private static final String SUPER_ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000002";

    private UUID seedGlobalAdmin() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Root Admin", "admin-" + userId + "@smart.coffee", "admin-" + userId + "@smart.coffee", "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, '" + SUPER_ADMIN_ROLE_ID + "', null, ?)", UUID.randomUUID(), userId,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of()));
        return userId;
    }

    private UUID newStore(CurrentActor actor) {
        String code = "RPM" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return storeManagement.create(actor, new CreateStoreRequest(code, "RPM Branch", "Addr", null,
                "Asia/Phnom_Penh", "USD", "km-KH", true)).storeId();
    }

    private UUID roleId(String code) {
        return UUID.fromString(jdbc.queryForObject("select role_id from identity.roles where role_code = ?",
                String.class, code));
    }

    /** Create an isolated scratch role so mutations never disturb global seed roles. */
    private UUID scratchRole() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into identity.roles (role_id, role_code, role_name) values (?::uuid, ?, ?)",
                id.toString(), "SCRATCH_" + id, "Scratch Role");
        return id;
    }

    private void grant(UUID roleId, String permissionCode) {
        jdbc.update("insert into identity.role_permissions (role_id, permission_id) "
                + "select ?::uuid, p.permission_id from identity.permissions p where p.permission_code = ?"
                + " on conflict do nothing", roleId.toString(), permissionCode);
    }

    private void assignRole(UUID userId, UUID roleId, UUID storeId) {
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, now())",
                UUID.randomUUID().toString(), userId.toString(), roleId.toString(),
                storeId == null ? null : storeId.toString());
    }

    private long securityVersion(UUID userId) {
        Long v = jdbc.queryForObject("select security_version from identity.users where user_id = ?::uuid",
                Long.class, userId.toString());
        return v == null ? -1 : v;
    }

    @Test
    void globalAdminResolvesRolePermissionsAndListsRolesAndPermissions() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        assertTrue(actor.hasPermission(PermissionCodes.ROLE_READ), "V024 must grant role:read to admins");
        assertTrue(actor.hasPermission(PermissionCodes.ROLE_PERMISSION_MANAGE));
        assertTrue(actor.hasPermission(PermissionCodes.PERMISSION_READ));

        List<RoleResponse> all = roles.listRoles();
        RoleResponse superAdmin = all.stream().filter(r -> r.roleCode().equals("SUPER_ADMIN")).findFirst().orElseThrow();
        assertTrue(superAdmin.permissionCodes().contains(PermissionCodes.ROLE_PERMISSION_MANAGE),
                "cross-join must have granted role:permission-manage to SUPER_ADMIN");

        List<PermissionResponse> perms = roles.listPermissions();
        PermissionResponse staffRead = perms.stream().filter(p -> p.permissionCode().equals("staff:read"))
                .findFirst().orElseThrow();
        assertEquals("staff", staffRead.category(), "category is safely derived from the code prefix");
        assertTrue(perms.stream().anyMatch(p -> p.permissionCode().equals("role:read")));

        // Detail matches the list view for the same role.
        assertEquals(superAdmin.permissionCodes(), roles.roleDetail(superAdmin.roleId()).permissionCodes());

        SecurityContextHolder.clearContext();
    }

    @Test
    void updateReplacesGrantSetAndInvalidatesMembers() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        UUID storeId = newStore(actor);
        StaffResponse member = staff.create(actor,
                new CreateStaffRequest("RPM Worker", "rpm-" + UUID.randomUUID() + "@smart.coffee", "Str0ng!Pass", null,
                        "en", "BARISTA", storeId, null));

        UUID role = scratchRole();
        grant(role, "branch:read");
        assignRole(member.userId(), role, storeId);
        long before = securityVersion(member.userId());

        RoleResponse updated = roles.updateRolePermissions(actor, role, List.of("order:read", "pos:access"));
        assertEquals(List.of("order:read", "pos:access"), updated.permissionCodes(),
                "replacement must drop branch:read and add exactly the requested set");
        assertTrue(securityVersion(member.userId()) > before,
                "active members must be invalidated so new authority takes effect");

        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsUnknownPermission_protectsSuperAdmin_andDedupesInput() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        UUID role = scratchRole();

        ValidationException unknown = assertThrows(ValidationException.class,
                () -> roles.updateRolePermissions(actor, role, List.of("does:not:exist")));
        assertEquals("PERMISSION_UNKNOWN", unknown.code());

        ForbiddenException protectedRole = assertThrows(ForbiddenException.class,
                () -> roles.updateRolePermissions(actor, roleId("SUPER_ADMIN"), List.of("order:read")));
        assertEquals("PROTECTED_ROLE", protectedRole.code());

        RoleResponse deduped = roles.updateRolePermissions(actor, role, List.of("order:read", "order:read", "pos:access"));
        assertEquals(List.of("order:read", "pos:access"), deduped.permissionCodes());

        assertThrows(ResourceNotFoundException.class, () -> roles.roleDetail(UUID.randomUUID()));

        SecurityContextHolder.clearContext();
    }

    @Test
    void storeScopedMemberCannotManageRoles() {
        UUID adminId = seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID storeId = newStore(admin);
        StaffResponse barista = staff.create(admin,
                new CreateStaffRequest("No Rights", "nr-" + UUID.randomUUID() + "@smart.coffee", "Str0ng!Pass", null,
                        "en", "BARISTA", storeId, null));

        // Re-resolve the actor as the store-scoped barista (not an administrator).
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(barista.userId().toString(), null, java.util.List.of()));
        CurrentActor actor = actors.require();
        assertEquals(barista.userId(), actor.userId());
        assertFalse(actor.hasPermission(PermissionCodes.ROLE_PERMISSION_MANAGE),
                "V024 must NOT grant role:permission-manage to store-scoped roles");
        assertFalse(actor.hasPermission(PermissionCodes.ROLE_READ));
        assertFalse(actor.hasPermission(PermissionCodes.PERMISSION_READ));

        SecurityContextHolder.clearContext();
        assertNotNull(adminId);
    }
}
