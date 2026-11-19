package com.smartcoffee.modules.ordering;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.ordering.api.ManagementOrderDetail;
import com.smartcoffee.modules.ordering.api.ManagementOrderSummary;
import com.smartcoffee.modules.ordering.application.OrderAdminQueryService;
import com.smartcoffee.modules.organization.api.CreateStoreRequest;
import com.smartcoffee.modules.organization.application.StoreManagementService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
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
 * Orders Administration search verification against a real migrated database.
 * Confirms store scoping (admins see all / one store, store managers see only their
 * branch and are denied other stores), filter + approved-sort behavior, the bounded
 * page size, and management authorization on the detail read. Service-level, mirroring
 * the other management ITs; orders are seeded with direct SQL so the test does not
 * depend on the full checkout pipeline.
 */
class OrderManagementAccessIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    StoreManagementService storeManagement;
    @Autowired
    OrderAdminQueryService service;

    private static final String SUPER_ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000002";

    private UUID seedUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Test User", email, email, "en-US");
        return userId;
    }

    private UUID seedGlobalAdmin() {
        UUID userId = seedUser("admin-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, '" + SUPER_ADMIN_ROLE_ID + "', null, ?)", UUID.randomUUID(), userId,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of()));
        return userId;
    }

    private UUID roleIdByCode(String code) {
        return UUID.fromString(jdbc.queryForObject("select role_id from identity.roles where role_code = ?",
                String.class, code));
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, java.util.List.of()));
    }

    private UUID newStore(CurrentActor actor) {
        String code = "ORD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return storeManagement.create(actor, new CreateStoreRequest(code, "Order Branch", "Addr", null,
                "Asia/Phnom_Penh", "USD", "km-KH", true)).storeId();
    }

    /** Minimal valid order header row (customer_id/cart_id nullable; money defaults to 0). */
    private UUID insertOrder(String number, UUID storeId, String status, String paymentStatus, double total,
            String isoTs) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, source, fulfillment_type, status, "
                + "payment_status, currency_code, subtotal, total_amount, total_quantity, ordered_at, updated_at) "
                + "values (?::uuid, ?, ?::uuid, 'POS', 'DINE_IN', ?, ?, 'USD', ?, ?, 2, ?::timestamptz, ?::timestamptz)",
                id.toString(), number, storeId.toString(), status, paymentStatus,
                BigDecimal.valueOf(total), BigDecimal.valueOf(total), isoTs, isoTs);
        return id;
    }

    private static boolean has(List<ManagementOrderSummary> rows, UUID id) {
        return rows.stream().anyMatch(r -> r.orderId().equals(id));
    }

    /**
     * The IT database is shared by the whole suite, so orders created by other tests
     * are newer than the rows seeded here. Page through the unfiltered global search
     * instead of assuming page 0 already contains them.
     */
    private List<ManagementOrderSummary> allVisible(CurrentActor admin) {
        List<ManagementOrderSummary> rows = new java.util.ArrayList<>();
        for (int page = 0; page < 50; page++) {
            Page<ManagementOrderSummary> result = service.search(admin, null, null, null, null, null, null, null, null,
                    null, "orderedAt", page, 100);
            rows.addAll(result.getContent());
            if (!result.hasNext()) {
                break;
            }
        }
        return rows;
    }

    @Test
    void globalAdminSearchesAcrossStoresWithFiltersAndApprovedSort() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID storeA = newStore(admin);
        UUID storeB = newStore(admin);
        UUID a1 = insertOrder("AAA100", storeA, "CONFIRMED", "PAID", 100, "2026-01-01T10:00:00Z");
        UUID a2 = insertOrder("AAA200", storeA, "READY", "PAID", 50, "2026-01-02T10:00:00Z");
        UUID b1 = insertOrder("BBB100", storeB, "CONFIRMED", "PENDING", 200, "2026-01-03T10:00:00Z");

        // No filters: all three visible to a global admin.
        List<ManagementOrderSummary> all = allVisible(admin);
        assertTrue(has(all, a1) && has(all, a2) && has(all, b1));

        // Store filter.
        List<ManagementOrderSummary> onlyA = service.search(admin, storeA, null, null, null, null, null, null, null,
                null, "orderedAt", 0, 20).getContent();
        assertTrue(onlyA.stream().allMatch(r -> r.storeId().equals(storeA)));
        assertTrue(has(onlyA, a1) && has(onlyA, a2) && !has(onlyA, b1));

        // Status filter, scoped to the fresh store so pre-existing test data cannot
        // leak into the count. storeA has a1=CONFIRMED, a2=READY.
        List<ManagementOrderSummary> confirmedA = service.search(admin, storeA, null, null, "CONFIRMED", null, null,
                null, null, null, "orderedAt", 0, 20).getContent();
        assertEquals(1, confirmedA.size());
        assertTrue(has(confirmedA, a1) && !has(confirmedA, a2));

        // Payment-status filter, scoped to storeA (a1, a2 both PAID).
        assertEquals(2, service.search(admin, storeA, null, null, null, "PAID", null, null, null, null, "orderedAt", 0,
                20).getTotalElements());

        // Free-text order-number filter (substring).
        assertTrue(has(service.search(admin, null, "AAA", null, null, null, null, null, null, null, "orderedAt", 0, 20)
                .getContent(), a1));

        // Approved sort within a bounded scope: totalAmount DESC → storeA 100 first.
        List<ManagementOrderSummary> byTotal = service.search(admin, storeA, null, null, null, null, null, null, null,
                null, "totalAmount", 0, 20).getContent();
        assertEquals(0, new BigDecimal("100").compareTo(byTotal.get(0).totalAmount()));

        SecurityContextHolder.clearContext();
    }

    @Test
    void storeScopedManagerSeesOnlyOwnStoreAndIsDeniedOthers() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID storeA = newStore(admin);
        UUID storeB = newStore(admin);
        UUID a1 = insertOrder("MGR-A1", storeA, "CONFIRMED", "PAID", 10, "2026-02-01T10:00:00Z");
        UUID b1 = insertOrder("MGR-B1", storeB, "CONFIRMED", "PAID", 20, "2026-02-01T11:00:00Z");

        UUID mgr = seedUser("mgr-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), mgr, roleIdByCode("STORE_MANAGER"),
                storeA.toString());
        authenticate(mgr);
        CurrentActor manager = actors.require();
        assertFalse(manager.globalAccess());
        assertTrue(manager.storeIds().contains(storeA));

        List<ManagementOrderSummary> scoped = service.search(manager, null, null, null, null, null, null, null, null,
                null, "orderedAt", 0, 20).getContent();
        assertTrue(has(scoped, a1));
        assertTrue(scoped.stream().allMatch(r -> r.storeId().equals(storeA)));
        assertFalse(has(scoped, b1));

        // Explicit request for a store the manager does not own is refused.
        ForbiddenException denied = assertThrows(ForbiddenException.class,
                () -> service.search(manager, storeB, null, null, null, null, null, null, null, null, "orderedAt", 0,
                        20));
        assertEquals("STORE_ACCESS_DENIED", denied.code());

        SecurityContextHolder.clearContext();
    }

    @Test
    void detailEnforcesManagementStoreAuthorization() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID storeA = newStore(admin);
        UUID storeB = newStore(admin);
        UUID a1 = insertOrder("DTL-A1", storeA, "CONFIRMED", "PAID", 10, "2026-03-01T10:00:00Z");
        UUID b1 = insertOrder("DTL-B1", storeB, "CONFIRMED", "PAID", 20, "2026-03-01T10:00:00Z");

        // Global admin can read either.
        ManagementOrderDetail detail = service.detail(admin, a1);
        assertEquals(storeA, detail.order().storeId());
        assertNotNull(detail.order().updatedAt());
        assertTrue(detail.items().isEmpty());
        assertDoesNotThrow(() -> service.detail(admin, b1));

        // A store-A-only manager can read a1 but not b1.
        UUID mgr = seedUser("mgr2-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), mgr, roleIdByCode("STORE_MANAGER"),
                storeA.toString());
        authenticate(mgr);
        CurrentActor manager = actors.require();
        assertDoesNotThrow(() -> service.detail(manager, a1));
        ForbiddenException denied = assertThrows(ForbiddenException.class, () -> service.detail(manager, b1));
        assertEquals("STORE_ACCESS_DENIED", denied.code());

        assertThrows(ResourceNotFoundException.class, () -> service.detail(admin, UUID.randomUUID()));

        SecurityContextHolder.clearContext();
    }

    @Test
    void pageSizeIsBounded() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID storeA = newStore(admin);
        insertOrder("BND-1", storeA, "CONFIRMED", "PAID", 1, "2026-04-01T10:00:00Z");

        Page<ManagementOrderSummary> result = service.search(admin, null, null, null, null, null, null, null, null,
                null, "orderedAt", 0, 1000);
        assertEquals(100, result.getSize(), "unbounded size must be clamped to the 100-row cap");

        SecurityContextHolder.clearContext();
    }

    @Test
    void orderAdminReadIsGrantedToAdminsAndManagersNotBaristas() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        assertTrue(admin.hasPermission(PermissionCodes.ORDER_ADMIN_READ),
                "V025 must grant order:admin-read to global admins");

        UUID storeA = newStore(admin);

        UUID mgr = seedUser("ordmgr-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), mgr, roleIdByCode("STORE_MANAGER"),
                storeA.toString());
        authenticate(mgr);
        assertTrue(actors.require().hasPermission(PermissionCodes.ORDER_ADMIN_READ),
                "STORE_MANAGER must hold order:admin-read for branch-scoped admin search");

        UUID barista = seedUser("ordbar-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), barista, roleIdByCode("BARISTA"),
                storeA.toString());
        authenticate(barista);
        assertFalse(actors.require().hasPermission(PermissionCodes.ORDER_ADMIN_READ),
                "baristas must not gain administrative order read through V025");

        SecurityContextHolder.clearContext();
    }
}
