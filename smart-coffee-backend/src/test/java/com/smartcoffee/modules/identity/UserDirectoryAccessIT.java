package com.smartcoffee.modules.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.identity.api.ManagementUserDetail;
import com.smartcoffee.modules.identity.api.ManagementUserSummary;
import com.smartcoffee.modules.identity.application.UserDirectoryService;
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
 * User Directory (Full-stack wiring §1.1) against a real migrated database:
 * the V028 permission grant matrix (global admins yes, managers/baristas no),
 * search filters (text, accountType STAFF/CUSTOMER, roleCode, storeId), the
 * bounded page, the detail read (assignment history + customer profile
 * slice), and input hardening. Seeding uses direct SQL like the other
 * management ITs so the test does not depend on registration flows.
 */
@SuppressWarnings("null")
class UserDirectoryAccessIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    UserDirectoryService service;

    private static final String SUPER_ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000002";

    private UUID seedUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Dir User", email, email, "en-US");
        return userId;
    }

    private UUID seedGlobalAdmin() {
        UUID userId = seedUser("diradmin-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, '" + SUPER_ADMIN_ROLE_ID + "', null, ?)", UUID.randomUUID(), userId,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return userId;
    }

    private UUID roleIdByCode(String code) {
        return UUID.fromString(jdbc.queryForObject("select role_id from identity.roles where role_code = ?",
                String.class, code));
    }

    private UUID seedStore(String name) {
        UUID storeId = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?::uuid, ?, ?, 'Addr', 'Asia/Phnom_Penh', 'USD', 'km-KH', true, now(), now(), 0)",
                storeId.toString(), "DIR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), name);
        return storeId;
    }

    private void assign(UUID userId, String roleCode, UUID storeId) {
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), userId, roleIdByCode(roleCode),
                storeId == null ? null : storeId.toString());
    }

    private UUID seedCustomerProfile(UUID userId, String loyalty) {
        UUID customerId = UUID.randomUUID();
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, loyalty_number, birth_date, "
                + "marketing_opt_in, created_at, updated_at, version) values (?::uuid, ?::uuid, ?, date '1995-04-04', "
                + "false, now(), now(), 0)", customerId.toString(), userId.toString(), loyalty);
        return customerId;
    }

    private boolean contains(List<ManagementUserSummary> rows, UUID userId) {
        return rows.stream().anyMatch(r -> r.userId().equals(userId));
    }

    @Test
    void permissionGrantMatrixMatchesV028PrivacyModel() {
        UUID admin = seedGlobalAdmin();
        assertTrue(actors.require().hasPermission(PermissionCodes.USER_ADMIN_READ),
                "SUPER_ADMIN must hold user:admin-read via V028");

        UUID store = seedStore("Grant Store");
        UUID mgr = seedUser("dirmgr-" + UUID.randomUUID() + "@smart.coffee");
        assign(mgr, "STORE_MANAGER", store);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(mgr.toString(), null, List.of()));
        assertFalse(actors.require().hasPermission(PermissionCodes.USER_ADMIN_READ),
                "STORE_MANAGER must NOT gain the global directory (customer PII containment)");

        UUID barista = seedUser("dirbar-" + UUID.randomUUID() + "@smart.coffee");
        assign(barista, "BARISTA", store);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(barista.toString(), null, List.of()));
        assertFalse(actors.require().hasPermission(PermissionCodes.USER_ADMIN_READ));

        assertNotNull(admin);
        SecurityContextHolder.clearContext();
    }

    @Test
    void searchFiltersByAccountTypeRoleAndStore() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore("Filter Store");

        UUID staff = seedUser("dirstaff-" + UUID.randomUUID() + "@smart.coffee");
        assign(staff, "BARISTA", store);
        UUID customer = seedUser("dircust-" + UUID.randomUUID() + "@smart.coffee");
        seedCustomerProfile(customer, "LM" + UUID.randomUUID().toString().substring(0, 8));

        // Text search matches full_name/email only, so "dircust" hits exactly the
        // seeded customer (staff carries the "dirstaff" prefix) out of a mixed directory.
        Page<ManagementUserSummary> byText = service.list(admin, "dircust", null, null, null,
                "ALL", 0, 20);
        assertEquals(1, byText.getTotalElements());
        assertTrue(contains(byText.getContent(), customer));

        Page<ManagementUserSummary> staffOnly = service.list(admin, null, null, null, null, "STAFF", 0, 100);
        assertTrue(contains(staffOnly.getContent(), staff));
        assertFalse(contains(staffOnly.getContent(), customer), "pure customers are not STAFF");

        Page<ManagementUserSummary> customerOnly = service.list(admin, null, null, null, null, "CUSTOMER", 0, 100);
        assertTrue(contains(customerOnly.getContent(), customer));
        assertFalse(contains(customerOnly.getContent(), staff), "baristas without a profile are not CUSTOMER");

        // The customer flag on summaries mirrors the profile existence.
        assertTrue(customerOnly.getContent().stream().filter(r -> r.userId().equals(customer))
                .allMatch(ManagementUserSummary::customer));

        Page<ManagementUserSummary> byRole = service.list(admin, null, null, "BARISTA", store, "ALL", 0, 100);
        assertTrue(contains(byRole.getContent(), staff));
        assertFalse(contains(byRole.getContent(), customer));

        Page<ManagementUserSummary> byStore = service.list(admin, null, null, null, store, "ALL", 0, 100);
        assertTrue(contains(byStore.getContent(), staff));
        assertFalse(contains(byStore.getContent(), customer), "no assignment → outside the store filter");

        SecurityContextHolder.clearContext();
    }

    @Test
    void pageSizeIsBoundedAndBadAccountTypeRejected() {
        CurrentActor admin = authenticateAdmin();
        assertEquals(100, service.list(admin, null, null, null, null, "ALL", 0, 5000).getSize());
        assertThrows(ValidationException.class,
                () -> service.list(admin, null, null, null, null, "ROBOT", 0, 20));
        SecurityContextHolder.clearContext();
    }

    @Test
    void detailReturnsAssignmentHistoryAndCustomerSlice() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore("Detail Store");
        UUID user = seedUser("dir detail-" + UUID.randomUUID() + "@smart.coffee");
        assign(user, "BARISTA", store);
        // A revoked assignment must appear in history but not as an active role.
        UUID revoked = UUID.randomUUID();
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at, "
                + "revoked_at) values (?::uuid, ?::uuid, (select role_id from identity.roles where role_code='CASHIER'), "
                + "?::uuid, now(), now())", revoked.toString(), user.toString(), store.toString());
        UUID customerId = seedCustomerProfile(user, "LM9" + UUID.randomUUID().toString().substring(0, 7));

        ManagementUserDetail detail = service.detail(admin, user);
        assertEquals(user, detail.user().userId());
        assertTrue(detail.user().customer());
        assertEquals(List.of("BARISTA"), detail.user().roles(), "only the active assignment is a current role");
        assertEquals(2, detail.assignments().size());
        assertEquals(1, detail.assignments().stream().filter(a -> !a.active()).count());
        assertNotNull(detail.customerProfile());
        assertEquals(customerId, detail.customerProfile().customerId());
        assertEquals("LM9" + "", detail.customerProfile().loyaltyNumber().substring(0, 3));

        assertThrows(ResourceNotFoundException.class, () -> service.detail(admin, UUID.randomUUID()));
        SecurityContextHolder.clearContext();
    }

    private CurrentActor authenticateAdmin() {
        seedGlobalAdmin();
        return actors.require();
    }
}
