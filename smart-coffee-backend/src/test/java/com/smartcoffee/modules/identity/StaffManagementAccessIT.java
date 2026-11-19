package com.smartcoffee.modules.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.identity.api.CreateStaffRequest;
import com.smartcoffee.modules.identity.api.StaffResponse;
import com.smartcoffee.modules.identity.api.StaffStatusRequest;
import com.smartcoffee.modules.identity.application.StaffManagementService;
import com.smartcoffee.modules.organization.api.CreateStoreRequest;
import com.smartcoffee.modules.organization.application.StoreManagementService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Staff Administration verification against a real migrated database (Flyway
 * through V023). Confirms the V023 permission seed reaches global administrators,
 * the create command enforces the store/global role rules and email uniqueness,
 * disabling bumps security_version, and the final-active-admin guard blocks a
 * lockout. Service-level (not HTTP), mirroring BranchManagementAccessIT.
 */
class StaffManagementAccessIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    StoreManagementService storeManagement;
    @Autowired
    StaffManagementService staff;

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

    /**
     * The shared Testcontainers database accumulates global administrators from
     * every IT method/class, so the "final admin" precondition must be isolated:
     * disable every active global admin except the one under test.
     */
    private void isolateGlobalAdmin(UUID keep) {
        jdbc.update("update identity.users set status='DISABLED' where status='ACTIVE' and user_id in ("
                + "select distinct a.user_id from identity.user_role_assignments a "
                + "join identity.roles r on r.role_id = a.role_id "
                + "where r.role_code in ('SUPER_ADMIN','ADMIN') and a.store_id is null and a.revoked_at is null "
                + "and (a.expires_at is null or a.expires_at > now())) and user_id <> ?::uuid",
                keep.toString());
    }

    private UUID newStore(CurrentActor actor) {
        String code = "STF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return storeManagement.create(actor, new CreateStoreRequest(code, "Staff Branch", "Addr", null,
                "Asia/Phnom_Penh", "USD", "km-KH", true)).storeId();
    }

    private int countActiveAssignments(UUID userId, String roleCode, UUID storeId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from identity.user_role_assignments a join identity.roles r on r.role_id=a.role_id "
                        + "where a.user_id=?::uuid and r.role_code=? and a.revoked_at is null "
                        + "and ((?::uuid is null and a.store_id is null) or a.store_id=?::uuid)",
                Integer.class, userId.toString(), roleCode, storeId == null ? null : storeId.toString(),
                storeId == null ? null : storeId.toString());
        return n == null ? 0 : n;
    }

    @Test
    void globalAdminResolvesStaffPermissionsAndCreatesStoreScopedBarista() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        assertTrue(actor.hasPermission(PermissionCodes.STAFF_READ), "V023 must grant staff:read to admins");
        assertTrue(actor.hasPermission(PermissionCodes.STAFF_CREATE));
        assertTrue(actor.hasPermission(PermissionCodes.STAFF_STATUS_MANAGE));
        assertTrue(actor.hasPermission(PermissionCodes.STAFF_ROLE_ASSIGN));

        UUID storeId = newStore(actor);
        String email = "barista-" + UUID.randomUUID() + "@smart.coffee";
        StaffResponse created = staff.create(actor,
                new CreateStaffRequest("Barista Bob", email, "Str0ng!Pass", "+85512345678", "km", "BARISTA", storeId,
                        null));

        assertNotNull(created.userId());
        assertEquals("ACTIVE", created.status());
        assertTrue(created.roles().contains("BARISTA"), "created staff must carry the BARISTA role");
        assertEquals("+85512345678", created.phone());
        assertEquals("km-KH", created.preferredLanguage(), "locale alias km must persist as km-KH");
        assertEquals(1, countActiveAssignments(created.userId(), "BARISTA", storeId));

        SecurityContextHolder.clearContext();
    }

    @Test
    void nonAdminRoleRequiresStoreAndAdminRoleRejectsStore() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        UUID storeId = newStore(actor);

        ValidationException noStore = assertThrows(ValidationException.class, () -> staff.create(actor,
                new CreateStaffRequest("No Branch", "nb-" + UUID.randomUUID() + "@smart.coffee", "Str0ng!Pass", null,
                        "en", "CASHIER", null, null)));
        assertEquals("STORE_REQUIRED", noStore.code());

        ValidationException adminWithStore = assertThrows(ValidationException.class, () -> staff.create(actor,
                new CreateStaffRequest("Bad Admin", "ba-" + UUID.randomUUID() + "@smart.coffee", "Str0ng!Pass", null,
                        "en", "ADMIN", storeId, null)));
        assertEquals("GLOBAL_ROLE_STORE_CONFLICT", adminWithStore.code());

        SecurityContextHolder.clearContext();
    }

    @Test
    void duplicateStaffEmailIsRejectedTyped() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        UUID storeId = newStore(actor);
        String email = "dup-" + UUID.randomUUID() + "@smart.coffee";

        staff.create(actor, new CreateStaffRequest("First", email, "Str0ng!Pass", null, "en", "BARISTA", storeId, null));
        ConflictException ex = assertThrows(ConflictException.class, () -> staff.create(actor,
                new CreateStaffRequest("Second", email, "Str0ng!Pass", null, "en", "KITCHEN_STAFF", storeId, null)));
        assertEquals("EMAIL_ALREADY_REGISTERED", ex.code());

        SecurityContextHolder.clearContext();
    }

    @Test
    void disableIncrementsSecurityVersionAndFinalAdminIsProtected() {
        UUID adminId = seedGlobalAdmin();
        CurrentActor actor = actors.require();
        UUID storeId = newStore(actor);
        StaffResponse member = staff.create(actor,
                new CreateStaffRequest("Worker", "wk-" + UUID.randomUUID() + "@smart.coffee", "Str0ng!Pass", null, "en",
                        "BARISTA", storeId, null));

        long before = jdbc.queryForObject("select security_version from identity.users where user_id=?::uuid",
                Long.class, member.userId().toString());
        staff.changeStatus(actor, member.userId(),
                new StaffStatusRequest(false, "resignation", member.version()));
        long after = jdbc.queryForObject("select security_version from identity.users where user_id=?::uuid",
                Long.class, member.userId().toString());
        String status = jdbc.queryForObject("select status from identity.users where user_id=?::uuid", String.class,
                member.userId().toString());
        assertEquals("DISABLED", status);
        assertTrue(after > before, "disabling must increment security_version to invalidate tokens");

        // Isolate the admin count so the seeded admin is provably the only active
        // global administrator; disabling them would lock the organisation out of
        // administration, so the guard must refuse it.
        isolateGlobalAdmin(adminId);
        StaffResponse adminView = staff.detail(adminId);
        ForbiddenException blocked = assertThrows(ForbiddenException.class, () -> staff.changeStatus(actor, adminId,
                new StaffStatusRequest(false, "test lockout", adminView.version())));
        assertEquals("LAST_ADMIN_PROTECTED", blocked.code());

        SecurityContextHolder.clearContext();
    }
}
