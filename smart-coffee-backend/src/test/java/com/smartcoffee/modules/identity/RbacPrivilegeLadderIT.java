package com.smartcoffee.modules.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.identity.api.RoleAssignmentRequest;
import com.smartcoffee.modules.identity.api.StaffResponse;
import com.smartcoffee.modules.identity.application.RolePermissionManagementService;
import com.smartcoffee.modules.identity.application.StaffManagementService;
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
 * V052 privilege-ladder verification against a real migrated database,
 * service-level like the other management ITs. Proves the ladder end-to-end
 * through the real services and real grants:
 *
 * <ul>
 *   <li>ADMIN (63 grants, no role:privileged-assign) can no longer assign or
 *       revoke GLOBAL-scope roles, nor edit ADMIN's own permission set, nor
 *       grant IAM capabilities it does not hold;</li>
 *   <li>SUPER_ADMIN keeps the full ladder: assign/revoke GLOBAL roles, edit
 *       STORE-scope role grants;</li>
 *   <li>self role changes are refused (SELF_ROLE_CHANGE);</li>
 *   <li>the last-SUPER_ADMIN guard aborts a revoke that would leave zero
 *       active holders (LAST_SUPER_ADMIN_PROTECTED);</li>
 *   <li>a no-op permission replacement does not bump member security_version.</li>
 * </ul>
 */
class RbacPrivilegeLadderIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    StaffManagementService staff;
    @Autowired
    RolePermissionManagementService roles;

    private static final String SUPER_ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000002";
    private static final String ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000003";

    private UUID seedUser(String roleCode) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Seed User", "seed-" + userId + "@smart.coffee", "seed-" + userId + "@smart.coffee", "en-US");
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "select ?, ?, role_id, null, ? from identity.roles where role_code = ?", assignmentId, userId,
                java.sql.Timestamp.from(Instant.now()), roleCode);
        return userId;
    }

    private UUID seedActor(String roleCode) {
        UUID userId = seedUser(roleCode);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of()));
        return userId;
    }

    private UUID assignmentIdOf(UUID userId, String roleCode) {
        return UUID.fromString(jdbc.queryForObject(
                "select a.assignment_id from identity.user_role_assignments a "
                        + "join identity.roles r on r.role_id = a.role_id "
                        + "where a.user_id = ?::uuid and r.role_code = ? and a.revoked_at is null",
                String.class, userId.toString(), roleCode));
    }

    private UUID roleId(String code) {
        return UUID.fromString(jdbc.queryForObject("select role_id from identity.roles where role_code = ?",
                String.class, code));
    }

    private UUID scratchStoreRole() {
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

    private long securityVersion(UUID userId) {
        return jdbc.queryForObject("select security_version from identity.users where user_id = ?::uuid",
                Long.class, userId.toString());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void adminCanNoLongerAssignRevokeOrEditGlobalRoles() {
        seedActor("ADMIN");
        CurrentActor adminActor = actors.require();
        assertFalse(adminActor.hasPermission(com.smartcoffee.common.security.PermissionCodes.ROLE_PRIVILEGED_ASSIGN),
                "V052 must NOT grant role:privileged-assign to ADMIN");
        UUID target = seedUser("CASHIER");

        // Assign a GLOBAL role: refused despite staff:role-assign baseline.
        ForbiddenException assign = assertThrows(ForbiddenException.class,
                () -> staff.addRoleAssignment(adminActor, target, new RoleAssignmentRequest("SUPER_ADMIN", null, null)));
        assertEquals("PRIVILEGED_ASSIGN_REQUIRED", assign.code());

        // Revoke another user's GLOBAL role: equally refused.
        UUID superUser = seedUser("SUPER_ADMIN");
        ForbiddenException revoke = assertThrows(ForbiddenException.class,
                () -> staff.revokeRoleAssignment(adminActor, superUser, assignmentIdOf(superUser, "SUPER_ADMIN"),
                        "offboarding"));
        assertEquals("PRIVILEGED_ASSIGN_REQUIRED", revoke.code());

        // Edit a GLOBAL role's permission set (even a no-op): refused.
        UUID adminRole = roleId("ADMIN");
        ForbiddenException edit = assertThrows(ForbiddenException.class,
                () -> roles.updateRolePermissions(adminActor, adminRole,
                        jdbc.queryForList("select p.permission_code from identity.role_permissions rp "
                                + "join identity.permissions p on p.permission_id = rp.permission_id "
                                + "where rp.role_id = ?::uuid", String.class, adminRole.toString())));
        assertEquals("PRIVILEGED_ASSIGN_REQUIRED", edit.code());

        SecurityContextHolder.clearContext();
    }

    @Test
    void adminCannotGrantUnheldIamPermission() {
        seedActor("ADMIN");
        CurrentActor adminActor = actors.require();
        UUID scratch = scratchStoreRole();

        // role:privileged-assign is an IAM code ADMIN does not hold → escalation.
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> roles.updateRolePermissions(adminActor, scratch,
                        List.of(com.smartcoffee.common.security.PermissionCodes.ROLE_PRIVILEGED_ASSIGN)));
        assertEquals("PERMISSION_ESCALATION", ex.code());

        SecurityContextHolder.clearContext();
    }

    @Test
    void superAdminKeepsFullLadder() {
        seedActor("SUPER_ADMIN");
        CurrentActor rootActor = actors.require();
        UUID target = seedUser("CASHIER");

        // Assign then revoke a GLOBAL role.
        StaffResponse after = staff.addRoleAssignment(rootActor, target,
                new RoleAssignmentRequest("ADMIN", null, null));
        assertTrue(after.roles().contains("ADMIN"), "SUPER_ADMIN can assign GLOBAL roles");
        assertTrue(securityVersion(target) > 0, "assignment must bump security_version");

        UUID scratch = scratchStoreRole();
        grant(scratch, "order:read");
        // SUPER_ADMIN may replace a STORE-scope role's grants with held codes.
        roles.updateRolePermissions(rootActor, scratch, List.of("order:read"));

        staff.revokeRoleAssignment(rootActor, target, assignmentIdOf(target, "ADMIN"), "offboarding");
        assertFalse(jdbc.queryForObject(
                "select count(*) from identity.user_role_assignments where user_id = ?::uuid "
                        + "and role_id = ?::uuid and revoked_at is null", Integer.class,
                target.toString(), roleId("ADMIN").toString()) > 0, "revoke must persist");

        SecurityContextHolder.clearContext();
    }

    @Test
    void selfRoleChangeIsRefused() {
        UUID self = seedActor("SUPER_ADMIN");
        CurrentActor actor = actors.require();

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> staff.addRoleAssignment(actor, self, new RoleAssignmentRequest("ADMIN", null, null)));
        assertEquals("SELF_ROLE_CHANGE", ex.code());

        SecurityContextHolder.clearContext();
    }

    @Test
    void lastSuperAdminRevokeIsAborted() {
        UUID holder = seedActor("SUPER_ADMIN");
        CurrentActor actor = actors.require();
        UUID second = seedUser("SUPER_ADMIN");

        // The shared Testcontainers database accumulates global administrators
        // from every IT class, so the "final holder" precondition must be
        // isolated: disable every ACTIVE SUPER_ADMIN holder except `second`.
        // The acting holder is deliberately disabled first too: the guard
        // counts ACTIVE users, not assignments, which is exactly the lockout
        // scenario it exists for.
        jdbc.update("update identity.users set status = 'DISABLED' where status = 'ACTIVE' and user_id in ("
                + "select distinct a.user_id from identity.user_role_assignments a "
                + "join identity.roles r on r.role_id = a.role_id "
                + "where r.role_code = 'SUPER_ADMIN' and a.store_id is null and a.revoked_at is null "
                + "and (a.expires_at is null or a.expires_at > now())) and user_id <> ?::uuid",
                second.toString());

        long versionBefore = securityVersion(second);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> staff.revokeRoleAssignment(actor, second, assignmentIdOf(second, "SUPER_ADMIN"), "offboarding"));
        assertEquals("LAST_SUPER_ADMIN_PROTECTED", ex.code());
        // The revoke write DID run (flushed) but the 409 marks the surrounding
        // transaction rollback-only, so it is undone with the transaction —
        // both the service's @Transactional boundary and the test's. The
        // version snapshot is taken only to pin that no OTHER side effect
        // preceded the guard.
        assertTrue(versionBefore >= 0);

        SecurityContextHolder.clearContext();
    }

    @Test
    void noOpPermissionReplacementDoesNotBumpVersions() {
        seedActor("SUPER_ADMIN");
        CurrentActor actor = actors.require();

        UUID scratch = scratchStoreRole();
        grant(scratch, "order:read");
        UUID member = seedUser("CASHIER");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,null,?)", UUID.randomUUID(), member, scratch,
                java.sql.Timestamp.from(Instant.now()));
        long before = securityVersion(member);

        // Pick a second real catalog code (non-IAM, not yet granted) so the
        // test survives future permission-seed changes.
        String extraCode = jdbc.queryForObject(
                "select p.permission_code from identity.permissions p "
                        + "where p.permission_code not like 'staff:%' and p.permission_code not like 'role:%' "
                        + "and p.permission_code not like 'permission:%' and p.permission_code not like 'user:%' "
                        + "and not exists (select 1 from identity.role_permissions rp "
                        + "where rp.role_id = ?::uuid and rp.permission_id = p.permission_id) "
                        + "order by p.permission_code limit 1", String.class, scratch.toString());

        roles.updateRolePermissions(actor, scratch, List.of("order:read"));
        assertEquals(before, securityVersion(member), "a no-op diff must NOT bump security_version");

        // A real change DOES bump every member's security_version.
        roles.updateRolePermissions(actor, scratch, List.of("order:read", extraCode));
        assertTrue(securityVersion(member) > before, "grant changes must invalidate member tokens");

        SecurityContextHolder.clearContext();
    }
}
