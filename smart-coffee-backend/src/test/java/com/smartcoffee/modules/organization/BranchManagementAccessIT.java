package com.smartcoffee.modules.organization;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.identity.api.AccessContextResponse;
import com.smartcoffee.modules.identity.application.AccessContextQueryService;
import com.smartcoffee.modules.organization.api.CreateStoreRequest;
import com.smartcoffee.modules.organization.api.ManagementStoreResponse;
import com.smartcoffee.modules.organization.api.UpdateStoreRequest;
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
 * Verifies, against a real migrated database (Flyway V001..V014), that:
 * - V013 actually grants branch:read/branch:manage to global administrators;
 * - CurrentActorProvider resolves those grants for a pure global admin;
 * - branch management create/update enforce optimistic locking;
 * - the access-context endpoint surfaces the admin's branch authority.
 *
 * <p>Runs under the shared {@code test} profile with strict
 * {@code ddl-auto=validate}; schema/type reconciliation lives in V014, so no
 * per-test validation override is required here.
 */
class BranchManagementAccessIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    StoreManagementService management;
    @Autowired
    AccessContextQueryService accessContext;

    private UUID seedGlobalAdmin() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Root Admin", "admin-" + userId + "@smart.coffee", "admin-" + userId + "@smart.coffee", "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, '00000000-0000-0000-0000-000000000002', null, ?)", UUID.randomUUID(), userId,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of()));
        return userId;
    }

    @Test
    void globalAdministratorResolvesBranchPermissionsAndManagesBranch() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();

        assertTrue(actor.globalAccess(), "SUPER_ADMIN store-NULL assignment must be global");
        assertTrue(actor.hasPermission(PermissionCodes.BRANCH_MANAGE),
                "V013 must grant branch:manage to global admins");
        assertTrue(actor.hasPermission(PermissionCodes.BRANCH_READ));

        String code = "IT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        ManagementStoreResponse created = management.create(actor,
                new CreateStoreRequest(code, "Integration Branch", "Somewhere", null, "Asia/Phnom_Penh", "USD", "km-KH",
                        null));
        assertNotNull(created.storeId());
        assertEquals(0L, created.version());

        // Stale version is rejected with the canonical conflict code.
        UpdateStoreRequest stale = new UpdateStoreRequest("Renamed", "Somewhere Else", null, "Asia/Phnom_Penh", "USD",
                "km-KH", 42L);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> management.update(actor, created.storeId(), stale));
        assertEquals("VERSION_CONFLICT", ex.code());

        AccessContextResponse context = accessContext.build(actor);
        assertEquals("ACTIVE", context.status());
        assertTrue(context.globalAccess());
        assertTrue(context.accessibleBranches().stream().anyMatch(b -> b.storeCode().equals(code)),
                "newly created active branch must appear in the admin's accessible branches");

        SecurityContextHolder.clearContext();
    }

    @Test
    void storeCodeUniquenessIsEnforced() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        String code = "DUP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        management.create(actor, new CreateStoreRequest(code, "First", "A", null, "Asia/Phnom_Penh", "USD", "km-KH",
                null));
        ConflictException ex = assertThrows(ConflictException.class, () -> management.create(actor,
                new CreateStoreRequest(code, "Second", "B", null, "Asia/Phnom_Penh", "USD", "km-KH", null)));
        assertEquals("STORE_CODE_TAKEN", ex.code());

        SecurityContextHolder.clearContext();
    }

    /**
     * Regression: the management list must work with no search text and no
     * status filter. The old JPQL bound null parameters that Postgres could not
     * type under pgjdbc binary transfer ("function lower(bytea) does not
     * exist" -> 500), so the sentinel path is asserted explicitly here.
     */
    @Test
    void listSearchAndStatusFilterResolveAgainstRealRows() {
        seedGlobalAdmin();
        CurrentActor actor = actors.require();
        String code = "LST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        management.create(actor,
                new CreateStoreRequest(code, "Listable Branch", "Addr", null, "Asia/Phnom_Penh", "USD", "km-KH", true));

        // No filters at all: previously crashed with lower(bytea). NOTE: the shared
        // IT database accumulates stores across test classes and the native search
        // query has no ORDER BY, so page-0 membership is deliberately NOT asserted —
        // it would be a false contract. What this regression must prove is that the
        // sentinel (empty-text, no-status) query resolves against real rows; row
        // attribution below uses the unique store code, which is deterministic.
        assertNotNull(management.list(null, null, 0, 50).getContent());
        var unfiltered = management.list("", null, 0, 50);
        assertNotNull(unfiltered.getContent());
        assertTrue(unfiltered.getTotalElements() >= 1,
                "sentinel query must resolve against real rows, not fabricate an empty total");
        assertTrue(unfiltered.getContent().stream().allMatch(s -> s.storeCode() != null),
                "every unfiltered page-0 row must be a materialized store");
        // Free-text match on code, then on name fragment.
        assertTrue(management.list(code, null, 0, 50).getContent().stream()
                .allMatch(s -> s.storeCode().equalsIgnoreCase(code) || s.storeName().toLowerCase().contains(code.toLowerCase())));
        assertTrue(management.list(code, null, 0, 50).getContent().stream().anyMatch(s -> s.storeCode().equals(code)));
        assertTrue(management.list("Listable", null, 0, 50).getContent().stream().anyMatch(s -> s.storeCode().equals(code)));
        // Status filters: active=true finds it, active=false must not.
        assertTrue(management.list(code, true, 0, 50).getContent().stream().anyMatch(s -> s.storeCode().equals(code)));
        assertTrue(management.list(code, false, 0, 50).getContent().stream().noneMatch(s -> s.storeCode().equals(code)));
        // Projection carries authoritative timestamps and lock version for the UI.
        ManagementStoreResponse row = management.list(code, null, 0, 50).getContent().stream()
                .filter(s -> s.storeCode().equals(code)).findFirst().orElseThrow();
        assertNotNull(row.createdAt());
        assertNotNull(row.updatedAt());
        assertEquals(0L, row.version());

        SecurityContextHolder.clearContext();
    }
}
