package com.smartcoffee.modules.production;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.production.application.ProductionWorkflowService;
import com.smartcoffee.modules.production.consumer.ProductionTicketConsumer;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checkpoint 15c — production start/ready store-scope (IDOR) enforcement.
 *
 * <p>The security audit found that {@code ProductionController} checked the
 * {@code production:item-start} / {@code production:item-ready} permission and
 * idempotency but NOT the branch the ticket belongs to: a cashier holding the
 * permission at branch A could action tickets at branch B by guessing an item
 * id. {@code ProductionWorkflowService} now loads the ticket and calls
 * {@code StoreAuthorizationService.requireStore(actor, ticket.storeId())}
 * before any state mutation.</p>
 *
 * <p>These tests drive the real workflow service against the migrated
 * database with tickets created by the real payment-completed consumer, so
 * the guard is proven on production data, not a stub.</p>
 */
class ProductionStoreScopeIT extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionTicketConsumer consumer;
    @Autowired ProductionWorkflowService workflow;

    // ------------------------------------------------------------- fixtures

    private UUID createStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD','en-US',true,now(),now(),0)",
                id, "PS" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "Store Scope IT Store",
                "Addr", "Asia/Phnom_Penh");
        return id;
    }

    private void seedStation(UUID storeId, String code, String type, int order) {
        jdbc.update("insert into production.production_stations (station_id, store_id, station_code, station_name, "
                + "station_type, display_order, active, version) values (?,?,?,?,?,?,true,0)",
                UUID.randomUUID(), storeId, code, code, type, order);
    }

    private UUID createConfirmedOrder(UUID storeId, String orderNumber) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, source, fulfillment_type, "
                + "status, payment_status, currency_code, subtotal, discount_amount, tax_amount, delivery_fee, "
                + "rounding_amount, total_amount, total_quantity, ordered_at, updated_at, version) "
                + "values (?,?,?,'POS','DINE_IN','CONFIRMED','PAID','USD',10,0,0,0,0,10,1,now(),now(),0)",
                orderId, orderNumber, storeId);
        jdbc.update("insert into ordering.order_items (order_item_id, order_id, station_code, "
                + "product_code_snapshot, product_name_snapshot, quantity, unit_base_price, "
                + "unit_customization_price, line_discount_amount, line_tax_amount, line_total, created_at) "
                + "values (?,?,?,'SKU','Flat White',1,?,0,0,0,?,now())",
                UUID.randomUUID(), orderId, "BARISTA", new BigDecimal("5.00"), new BigDecimal("5.00"));
        return orderId;
    }

    /** Creates the ticket through the real consumer and returns its single item id. */
    private UUID seedTicketItem(UUID store) {
        UUID order = createConfirmedOrder(store, "ORD-" + UUID.randomUUID());
        consumer.receive("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PaymentCompleted\","
                + "\"eventVersion\":1,\"payload\":{\"orderId\":\"" + order + "\"}}");
        // The real checkout leaves an ACTIVE reservation behind; start() consumes
        // it on the first item. Seed an (empty-line) reservation so the authorized
        // path exercises the whole workflow, not just the guard.
        jdbc.update("insert into inventory.inventory_reservations (reservation_id, store_id, order_id, status, "
                + "expires_at, created_at, updated_at, version) values (?,?,?,'ACTIVE',now() + interval '1 hour',"
                + "now(),now(),0)", UUID.randomUUID(), store, order);
        return jdbc.queryForObject(
                "select i.ticket_item_id from production.production_ticket_items i "
                        + "join production.production_tickets t on t.ticket_id = i.ticket_id "
                        + "where t.order_id = ?",
                UUID.class, order);
    }

    private CurrentActor actorFor(UUID... storeIds) {
        // The workflow persists the acting user id (item started_by, stock ledger),
        // so the actor must be a real identity.users row.
        UUID userId = UUID.randomUUID();
        String email = "scope-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Scope IT User", email, email, "en-US");
        return new CurrentActor(userId, "USER",
                Set.of("production:item-start", "production:item-ready"), Set.of(storeIds), false);
    }

    private String itemStatus(UUID itemId) {
        return jdbc.queryForObject(
                "select status from production.production_ticket_items where ticket_item_id = ?",
                String.class, itemId);
    }

    // ----------------------------------------------------------- behaviour

    @Test
    void actorFromForeignStoreCannotStartItemAndNothingMutates() {
        UUID storeA = createStore();
        seedStation(storeA, "BARISTA", "BARISTA", 10);
        UUID itemId = seedTicketItem(storeA);

        UUID storeB = createStore(); // the attacker's own, unrelated branch

        CurrentActor foreign = actorFor(storeB);
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> workflow.start(foreign, UUID.randomUUID(), itemId),
                "permission alone must not allow actioning another branch's ticket");
        assertEquals("STORE_ACCESS_DENIED", ex.code());

        assertEquals("PENDING", itemStatus(itemId),
                "the rejected attempt must leave the ticket item untouched (rolled back before mutation)");
    }

    @Test
    void actorAssignedToTicketStoreCanStartItem() {
        UUID storeA = createStore();
        seedStation(storeA, "BARISTA", "BARISTA", 10);
        UUID itemId = seedTicketItem(storeA);

        var response = workflow.start(actorFor(storeA), UUID.randomUUID(), itemId);

        assertEquals("PREPARING", itemStatus(itemId),
                "an authorized start must move the item to PREPARING");
        assertEquals(storeA, response.storeId());
    }

    @Test
    void actorFromForeignStoreCannotReadyItem() {
        UUID storeA = createStore();
        seedStation(storeA, "BARISTA", "BARISTA", 10);
        UUID itemId = seedTicketItem(storeA);
        workflow.start(actorFor(storeA), UUID.randomUUID(), itemId); // legal, puts item in PREPARING

        UUID storeB = createStore();
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> workflow.ready(actorFor(storeB), UUID.randomUUID(), itemId),
                "ready must enforce the same store scope as start");
        assertEquals("STORE_ACCESS_DENIED", ex.code());

        assertEquals("PREPARING", itemStatus(itemId),
                "the foreign ready attempt must not mutate the item");
    }

    @Test
    void globalActorCanStartAnyStoreItem() {
        UUID storeA = createStore();
        seedStation(storeA, "BARISTA", "BARISTA", 10);
        UUID itemId = seedTicketItem(storeA);

        UUID adminId = UUID.randomUUID();
        String adminEmail = "admin-" + adminId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                adminId, "Scope IT Admin", adminEmail, adminEmail, "en-US");
        CurrentActor admin = new CurrentActor(adminId, "USER",
                Set.of("production:item-start"), Set.of(), true);
        workflow.start(admin, UUID.randomUUID(), itemId);

        assertEquals("PREPARING", itemStatus(itemId),
                "globalAccess actors (e.g. admins) keep the existing behavior");
    }
}
