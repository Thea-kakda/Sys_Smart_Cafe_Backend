package com.smartcoffee.modules.ordering;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.application.InventoryReservationService;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.ordering.api.CancelOrderRequest;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.application.OrderCommandService;
import com.smartcoffee.modules.ordering.application.OrderQueryService;
import com.smartcoffee.modules.payment.api.ReceiptResponse;
import com.smartcoffee.modules.payment.application.ReceiptQueryService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Full-stack wiring §1.2–§1.4 against a real migrated database: customer
 * order history (ownership is structural), the receipt read access matrix
 * (owner / scoped staff / stranger), and the cancel/complete command state
 * machine including idempotent replay, store scoping, reservation release on
 * cancel, and the audit + outbox side effects. Orders/payments/receipts are
 * seeded with direct SQL so the tests do not depend on the checkout pipeline.
 */
class CustomerOrderHistoryReceiptCommandsIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    OrderQueryService queries;
    @Autowired
    OrderCommandService commands;
    @Autowired
    ReceiptQueryService receipts;
    @Autowired
    InventoryReservationService reservations;
    @Autowired
    InventoryBalanceRepository balances;

    private static final String COFFEE_BEAN = "70000000-0000-0000-0000-000000000001";

    private UUID seedUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Hist User", email, email, "en-US");
        return userId;
    }

    private UUID seedStore() {
        UUID storeId = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?::uuid, ?, 'Hist Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'km-KH', true, now(), now(), 0)",
                storeId.toString(), "HST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return storeId;
    }

    private UUID seedCustomer(UUID userId) {
        UUID customerId = UUID.randomUUID();
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, created_at, updated_at, version) "
                + "values (?::uuid, ?::uuid, now(), now(), 0)", customerId.toString(), userId.toString());
        return customerId;
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private UUID insertOrder(String number, UUID storeId, UUID customerId, String status, String paymentStatus,
            double total) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, customer_id, source, "
                + "fulfillment_type, status, payment_status, currency_code, subtotal, total_amount, total_quantity, "
                + "ordered_at, updated_at) values (?::uuid, ?, ?::uuid, ?::uuid, 'CUSTOMER_WEB', 'PICKUP', ?, ?, "
                + "'USD', ?, ?, 1, now(), now())",
                id.toString(), number, storeId.toString(), customerId == null ? null : customerId.toString(),
                status, paymentStatus, BigDecimal.valueOf(total), BigDecimal.valueOf(total));
        return id;
    }

    private void insertPaidPaymentAndReceipt(UUID orderId, UUID storeId) {
        UUID paymentId = UUID.randomUUID();
        jdbc.update("insert into payment.payments (payment_id, payment_reference, order_id, store_id, "
                + "payment_method_id, status, currency_code, amount, created_at, updated_at) "
                + "values (?::uuid, ?, ?::uuid, ?::uuid, '80000000-0000-0000-0000-000000000001', 'PAID', 'USD', 10, "
                + "now(), now())", paymentId.toString(), "REF" + paymentId, orderId.toString(), storeId.toString());
        UUID receiptId = UUID.randomUUID();
        jdbc.update("insert into payment.receipts (receipt_id, receipt_number, payment_id, order_id, store_id, status, "
                + "receipt_snapshot, issued_at, created_at) values (?::uuid, ?, ?::uuid, ?::uuid, ?::uuid, 'ISSUED', "
                + "'{\"total\":10}'::jsonb, now(), now())", receiptId.toString(), "RCP" + receiptId,
                paymentId.toString(), orderId.toString(), storeId.toString());
    }

    // ------------------------------------------------------------------ §1.2

    @Test
    void historyReturnsOnlyOwnOrdersNewestFirst() {
        UUID store = seedStore();
        UUID me = seedUser("me-" + UUID.randomUUID() + "@smart.coffee");
        UUID myCustomer = seedCustomer(me);
        UUID other = seedUser("other-" + UUID.randomUUID() + "@smart.coffee");
        UUID otherCustomer = seedCustomer(other);
        UUID o1 = insertOrder("H-OLD", store, myCustomer, "COMPLETED", "PAID", 10);
        insertOrder("H-NEW", store, myCustomer, "CONFIRMED", "PAID", 20);
        UUID foreign = insertOrder("H-FOREIGN", store, otherCustomer, "CONFIRMED", "PAID", 30);

        authenticate(me);
        Page<OrderResponse> page = queries.list(myCustomer, 0, 20);
        assertEquals(2, page.getTotalElements());
        List<OrderResponse> rows = page.getContent();
        assertEquals(2, rows.size());
        // newest-first orderedAt: H-NEW inserted second carries the later timestamp.
        assertTrue(rows.stream().allMatch(r -> r.orderId().equals(o1) || !r.orderId().equals(foreign)));
        assertEquals(0, new BigDecimal("20").compareTo(rows.get(0).totalAmount()));
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ §1.3

    @Test
    void receiptAccessMatrix() {
        UUID store = seedStore();
        UUID ownerUser = seedUser("rop-" + UUID.randomUUID() + "@smart.coffee");
        UUID ownerCustomer = seedCustomer(ownerUser);
        UUID orderId = insertOrder("R-1", store, ownerCustomer, "COMPLETED", "PAID", 10);
        insertPaidPaymentAndReceipt(orderId, store);

        // Owner reads their settled receipt with the immutable snapshot.
        authenticate(ownerUser);
        List<ReceiptResponse> own = receipts.receipt(actors.require(), orderId);
        assertEquals(1, own.size());
        assertEquals("ISSUED", own.get(0).status());
        assertEquals(10, own.get(0).snapshot().path("total").asInt());
        SecurityContextHolder.clearContext();

        // A stranger customer gets 404, never 403 — existence is not leaked.
        UUID stranger = seedUser("str-" + UUID.randomUUID() + "@smart.coffee");
        seedCustomer(stranger);
        authenticate(stranger);
        assertThrows(ResourceNotFoundException.class, () -> receipts.receipt(actors.require(), orderId));
        SecurityContextHolder.clearContext();

        // Global admin (order:admin-read, global scope) reads for re-print.
        UUID admin = seedUser("adm-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?::uuid, ?::uuid, '00000000-0000-0000-0000-000000000002', null, now())",
                UUID.randomUUID(), admin);
        authenticate(admin);
        assertEquals(1, receipts.receipt(actors.require(), orderId).size());

        // A store-scoped manager of ANOTHER store is refused.
        UUID otherStore = seedStore();
        UUID foreignMgr = seedUser("fmgr-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?::uuid, ?::uuid, (select role_id from identity.roles where "
                + "role_code='STORE_MANAGER'), ?::uuid, now())", UUID.randomUUID(), foreignMgr,
                otherStore.toString());
        authenticate(foreignMgr);
        ForbiddenException denied = assertThrows(ForbiddenException.class,
                () -> receipts.receipt(actors.require(), orderId));
        assertEquals("STORE_ACCESS_DENIED", denied.code());

        // Unknown order: even an admin gets 404, not an empty list.
        assertThrows(ResourceNotFoundException.class, () -> receipts.receipt(actors.require(), UUID.randomUUID()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void receiptListIsEmptyBeforeSettlement() {
        UUID store = seedStore();
        UUID user = seedUser("rs-" + UUID.randomUUID() + "@smart.coffee");
        UUID customer = seedCustomer(user);
        UUID orderId = insertOrder("R-EMPTY", store, customer, "CONFIRMED", "PENDING", 5);
        authenticate(user);
        assertTrue(receipts.receipt(actors.require(), orderId).isEmpty());
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ §1.4

    @Test
    void cancelDrivesStateMachineAuditsOutboxesAndReleasesReservation() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore();
        UUID order = insertOrder("C-1", store, null, "CONFIRMED", "PAID", 10);

        // Open a tracked balance and reserve against the order, so release is observable.
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id, current_stock, "
                + "reserved_stock, minimum_stock, average_unit_cost) values (?::uuid, ?::uuid, 100, 0, 0, 0.025)",
                store.toString(), COFFEE_BEAN);
        reservations.reserve(store, order, Map.of(UUID.fromString(COFFEE_BEAN), new BigDecimal("10")),
                Duration.ofMinutes(15));

        OrderResponse r = commands.cancel(admin, order, new CancelOrderRequest("customer request"));
        assertEquals("CANCELLED", r.status());
        assertEquals(0, new BigDecimal("100").compareTo(balances.find(store, UUID.fromString(COFFEE_BEAN))
                .orElseThrow().currentStock()));
        assertEquals(0, BigDecimal.ZERO.compareTo(balances.find(store, UUID.fromString(COFFEE_BEAN))
                .orElseThrow().reservedStock()));
        assertEquals("RELEASED", jdbc.queryForObject("select status from inventory.inventory_reservations "
                + "where order_id = ?::uuid", String.class, order.toString()));

        // Status history, audit trail, and the outbox event were all written.
        assertEquals(1, jdbc.queryForObject("select count(*) from ordering.order_status_history where order_id = "
                + "?::uuid and to_status='CANCELLED' and reason='customer request'", Integer.class, order.toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit.audit_logs where action='ORDER_CANCELLED' "
                + "and aggregate_id=?::uuid", Integer.class, order.toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from integration.outbox_events where event_type="
                + "'OrderCancelled' and aggregate_id=?::uuid", Integer.class, order.toString()));

        // Idempotent replay: already-cancelled is a no-op 200, no second event.
        OrderResponse replay = commands.cancel(admin, order, new CancelOrderRequest("customer request"));
        assertEquals("CANCELLED", replay.status());
        assertEquals(1, jdbc.queryForObject("select count(*) from integration.outbox_events where event_type="
                + "'OrderCancelled' and aggregate_id=?::uuid", Integer.class, order.toString()));

        // A COMPLETED order cannot be cancelled — genuine 409.
        UUID done = insertOrder("C-2", store, null, "COMPLETED", "PAID", 10);
        ConflictException conflict = assertThrows(ConflictException.class,
                () -> commands.cancel(admin, done, new CancelOrderRequest("too late")));
        assertEquals("ORDER_STATE_CONFLICT", conflict.code());

        assertThrows(ResourceNotFoundException.class,
                () -> commands.cancel(admin, UUID.randomUUID(), new CancelOrderRequest("ghost")));
        SecurityContextHolder.clearContext();
    }

    @Test
    void completeRequiresReadyAndIsIdempotent() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore();
        UUID confirmed = insertOrder("P-1", store, null, "CONFIRMED", "PAID", 10);
        assertThrows(ConflictException.class, () -> commands.complete(admin, confirmed));

        UUID ready = insertOrder("P-2", store, null, "READY", "PAID", 10);
        assertEquals("COMPLETED", commands.complete(admin, ready).status());
        assertEquals("COMPLETED", commands.complete(admin, ready).status(), "replay stays 200");
        assertEquals(1, jdbc.queryForObject("select count(*) from integration.outbox_events where event_type="
                + "'OrderCompleted' and aggregate_id=?::uuid", Integer.class, ready.toString()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void storeScopedManagerCanCommandOwnStoreOnly() {
        authenticateAdmin();
        UUID storeA = seedStore();
        UUID storeB = seedStore();
        UUID a = insertOrder("S-A", storeA, null, "CONFIRMED", "PAID", 1);
        UUID b = insertOrder("S-B", storeB, null, "CONFIRMED", "PAID", 1);

        UUID mgr = seedUser("cmgr-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?::uuid, ?::uuid, (select role_id from identity.roles where "
                + "role_code='STORE_MANAGER'), ?::uuid, now())", UUID.randomUUID(), mgr, storeA.toString());
        authenticate(mgr);
        CurrentActor manager = actors.require();
        assertTrue(manager.hasPermission("order:admin-write"), "V029 grants STORE_MANAGER");
        assertEquals("CANCELLED", commands.cancel(manager, a, new CancelOrderRequest("walk-in left")).status());
        ForbiddenException denied = assertThrows(ForbiddenException.class,
                () -> commands.cancel(manager, b, new CancelOrderRequest("not yours")));
        assertEquals("STORE_ACCESS_DENIED", denied.code());
        SecurityContextHolder.clearContext();
    }

    private CurrentActor authenticateAdmin() {
        UUID admin = seedUser("cadmin-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?::uuid, ?::uuid, '00000000-0000-0000-0000-000000000002', null, now())",
                UUID.randomUUID(), admin);
        authenticate(admin);
        return actors.require();
    }
}
