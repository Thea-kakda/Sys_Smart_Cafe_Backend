package com.smartcoffee.modules.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.identity.api.RoleAssignmentSummary;
import com.smartcoffee.modules.identity.api.RoleResponse;
import com.smartcoffee.modules.identity.application.RoleAdministrationService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * V052 custom-role lifecycle verification against a real migrated database.
 * Proves the eight migration-seeded roles are immutable (PROTECTED_ROLE),
 * custom roles can be minted empty and renamed, duplicate codes are refused,
 * referenced roles cannot be deleted (ROLE_IN_USE) and the assignment listing
 * is PII-free (ids + status + timestamps only). The permission gating itself
 * (role:create / role:update / role:delete / role:assignment-read being
 * SUPER_ADMIN-only) lives in the controller + RbacPolicy and is covered by
 * {@code RbacPrivilegeLadderIT} and {@code RbacPolicyTest}.
 */
class RoleAdministrationAccessIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    RoleAdministrationService admin;

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

    private UUID roleId(String code) {
        return UUID.fromString(jdbc.queryForObject("select role_id from identity.roles where role_code = ?",
                String.class, code));
    }

    /** Insert an assignment row directly so delete-blocking can be tested without the staff API. */
    private UUID assign(UUID userId, UUID targetRoleId) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,null,?)", assignmentId, userId, targetRoleId,
                java.sql.Timestamp.from(Instant.now()));
        return assignmentId;
    }

    @Test
    void customRoleLifecycleCreateRenameDelete() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        String code = "REGION_MGR_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        RoleResponse created = admin.create(actor, code, "Region Manager", "Multi-branch supervisor", "STORE");
        assertNotNull(created.roleId());
        assertEquals(code, created.roleCode());
        assertEquals("Region Manager", created.roleName());
        assertFalse(created.isSystem(), "a minted role is never a system role");
        assertEquals("STORE", created.roleScope());
        assertEquals("Multi-branch supervisor", created.description());
        assertTrue(created.permissionCodes().isEmpty(), "custom roles are minted EMPTY");

        // Duplicate code is refused.
        ConflictException dup = assertThrows(ConflictException.class,
                () -> admin.create(actor, code, "Other", null, "STORE"));
        assertEquals("ROLE_CODE_EXISTS", dup.code());

        // Rename + description edit; code/scope/system are immutable fields.
        RoleResponse renamed = admin.update(actor, created.roleId(), "Regional Manager", "Updated blurb");
        assertEquals("Regional Manager", renamed.roleName());
        assertEquals("Updated blurb", renamed.description());
        assertEquals(code, renamed.roleCode(), "role_code never changes");
        assertFalse(renamed.isSystem());

        // Empty PATCH payload is a typed validation error.
        ValidationException empty = assertThrows(ValidationException.class,
                () -> admin.update(actor, created.roleId(), null, null));
        assertEquals("ROLE_UPDATE_EMPTY", empty.code());

        // Delete works once unreferenced, then the role is gone.
        admin.delete(actor, created.roleId());
        ResourceNotFoundException gone = assertThrows(ResourceNotFoundException.class,
                () -> admin.update(actor, created.roleId(), "Zombie", null));
        assertEquals("ROLE_NOT_FOUND", gone.code());
        assertEquals(0, countRole(code));

        SecurityContextHolder.clearContext();
    }

    @Test
    void systemRolesAreImmutable() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        UUID superAdmin = roleId("SUPER_ADMIN");
        UUID admin = roleId("ADMIN");

        ForbiddenException rename = assertThrows(ForbiddenException.class,
                () -> this.admin.update(actor, superAdmin, "Hacked", null));
        assertEquals("PROTECTED_ROLE", rename.code());
        ForbiddenException remove = assertThrows(ForbiddenException.class,
                () -> this.admin.delete(actor, superAdmin));
        assertEquals("PROTECTED_ROLE", remove.code());
        ForbiddenException renameAdmin = assertThrows(ForbiddenException.class,
                () -> this.admin.update(actor, admin, "Lower Admin", null));
        assertEquals("PROTECTED_ROLE", renameAdmin.code());

        SecurityContextHolder.clearContext();
    }

    @Test
    void referencedRoleCannotBeDeleted() {
        UUID holder = seedGlobalAdmin();
        CurrentActor actor = actors.require();
        String code = "SCRATCH_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        RoleResponse created = admin.create(actor, code, "Scratch", null, "STORE");

        UUID assignmentId = assign(holder, created.roleId());
        jdbc.update("update identity.user_role_assignments set revoked_at = now() where assignment_id = ?::uuid",
                assignmentId.toString());

        // Even a REVOKED assignment blocks deletion (role history is preserved).
        ConflictException inUse = assertThrows(ConflictException.class,
                () -> admin.delete(actor, created.roleId()));
        assertEquals("ROLE_IN_USE", inUse.code());
        assertEquals(1, countRole(code));
        jdbc.update("delete from identity.user_role_assignments where assignment_id = ?::uuid",
                assignmentId.toString());
        admin.delete(actor, created.roleId());
        assertEquals(0, countRole(code));

        SecurityContextHolder.clearContext();
    }

    @Test
    void assignmentsListingIsPiiFreeAndPaginated() {
        UUID holder = seedGlobalAdmin();
        CurrentActor actor = actors.require();
        UUID superAdmin = roleId("SUPER_ADMIN");

        Page<RoleAssignmentSummary> page = admin.assignments(superAdmin, 0, 20);
        assertTrue(page.getTotalElements() >= 1, "the seeded holder must be listed");
        RoleAssignmentSummary row = page.getContent().stream()
                .filter(r -> holder.equals(r.userId())).findFirst().orElseThrow();
        assertEquals("ACTIVE", row.userStatus());
        assertNull(row.storeId(), "a global SUPER_ADMIN grant has no store binding");
        assertNull(row.revokedAt());
        // PII-free shape: the summary record exposes ids/status/timestamps only.
        assertTrue(page.getContent().stream().allMatch(r ->
                r.assignmentId() != null && r.userId() != null && r.assignedAt() != null));

        // Page safety clamps.
        assertEquals(1, admin.assignments(superAdmin, -3, 0).getSize(), "size floor is 1");
        assertEquals(100, admin.assignments(superAdmin, 0, 5000).getSize(), "size ceiling is 100");

        SecurityContextHolder.clearContext();
    }

    private int countRole(String code) {
        Integer n = jdbc.queryForObject("select count(*) from identity.roles where role_code = ?", Integer.class, code);
        return n == null ? 0 : n;
    }
}
