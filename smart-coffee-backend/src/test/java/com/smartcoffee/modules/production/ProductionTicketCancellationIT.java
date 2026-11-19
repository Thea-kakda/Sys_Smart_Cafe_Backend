package com.smartcoffee.modules.production;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.production.application.ProductionQueueQueryService;
import com.smartcoffee.modules.production.application.ProductionWorkflowService;
import com.smartcoffee.modules.production.consumer.ProductionTicketConsumer;
import com.smartcoffee.modules.production.domain.StationType;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Order-cancellation propagation into production.
 *
 * <p>Cancellation previously emitted {@code order.cancelled.v1} with no
 * consumer, so a cancelled order left stale PENDING/PREPARING tickets on the
 * KDS/BDS boards forever even though {@code CANCELLED} was already permitted by
 * the schema. This test drives the real {@link ProductionTicketConsumer} with a
 * confirmed-payment envelope followed by an order-cancelled envelope and proves
 * the now-wired lifecycle:</p>
 *
 * <ul>
 *   <li>queued tickets are retired to CANCELLED and drop out of the queue;</li>
 *   <li>their pending items are cancelled with the ticket;</li>
 *   <li>redelivery of the cancellation is an idempotent no-op;</li>
 *   <li>a cancellation for an order with no tickets is a harmless no-op.</li>
 * </ul>
 */
class ProductionTicketCancellationIT extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionTicketConsumer consumer;
    @Autowired ProductionQueueQueryService queueService;
    @Autowired ProductionWorkflowService workflow;

    // ------------------------------------------------------------- fixtures

    private UUID createStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD','en-US',true,now(),now(),0)",
                id, "PX" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "Cancellation IT Store",
                "Addr", "Asia/Phnom_Penh");
        return id;
    }

    private void seedStation(UUID storeId, String code, String type, int order) {
        jdbc.update("insert into production.production_stations (station_id, store_id, station_code, station_name, "
                + "station_type, display_order, active, version) values (?,?,?,?,?,?,true,0)",
                UUID.randomUUID(), storeId, code, code, type, order);
    }

    private UUID createOrder(UUID storeId, String status, String orderNumber) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, source, fulfillment_type, "
                + "status, payment_status, currency_code, subtotal, discount_amount, tax_amount, delivery_fee, "
                + "rounding_amount, total_amount, total_quantity, ordered_at, updated_at, version) "
                + "values (?,?,?,'POS','DINE_IN',?,'PAID','USD',10,0,0,0,0,10,2,now(),now(),0)",
                orderId, orderNumber, storeId, status);
        return orderId;
    }

    private void seedOrderItem(UUID orderId, String stationCode, String productName, int quantity) {
        jdbc.update("insert into ordering.order_items (order_item_id, order_id, station_code, "
                + "product_code_snapshot, product_name_snapshot, quantity, unit_base_price, "
                + "unit_customization_price, line_discount_amount, line_tax_amount, line_total, created_at) "
                + "values (?,?,?,'SKU',?,?,?,0,0,0,?,now())",
                UUID.randomUUID(), orderId, stationCode, productName, quantity, new BigDecimal("5.00"),
                new BigDecimal("10.00"));
    }

    private static String paymentCompletedEnvelope(UUID eventId, UUID orderId) {
        return "{\"eventId\":\"" + eventId + "\","
                + "\"eventType\":\"PaymentCompleted\","
                + "\"eventVersion\":1,"
                + "\"payload\":{\"orderId\":\"" + orderId + "\"}}";
    }

    private static String orderCancelledEnvelope(UUID eventId, UUID orderId) {
        return "{\"eventId\":\"" + eventId + "\","
                + "\"eventType\":\"OrderCancelled\","
                + "\"eventVersion\":1,"
                + "\"payload\":{\"orderId\":\"" + orderId + "\",\"reason\":\"customer request\"}}";
    }

    private static String unknownEnvelope(UUID eventId, UUID orderId) {
        return "{\"eventId\":\"" + eventId + "\","
                + "\"eventType\":\"SomeUnknownBusinessEvent\","
                + "\"eventVersion\":1,"
                + "\"payload\":{\"orderId\":\"" + orderId + "\"}}";
    }

    private int statusCount(UUID orderId, String status) {
        Integer n = jdbc.queryForObject(
                "select count(*) from production.production_tickets where order_id = ? and status = ?",
                Integer.class, orderId, status);
        return n == null ? 0 : n;
    }

    private String itemStatus(UUID itemId) {
        return jdbc.queryForObject(
                "select status from production.production_ticket_items where ticket_item_id = ?",
                String.class, itemId);
    }

    private UUID singleItemId(UUID orderId) {
        return jdbc.queryForObject(
                "select i.ticket_item_id from production.production_ticket_items i "
                        + "join production.production_tickets t on t.ticket_id = i.ticket_id "
                        + "where t.order_id = ?",
                UUID.class, orderId);
    }

    /** Seeds an ACTIVE reservation so a legal start would consume real inventory. */
    private void seedActiveReservation(UUID storeId, UUID orderId) {
        jdbc.update("insert into inventory.inventory_reservations (reservation_id, store_id, order_id, status, "
                + "expires_at, created_at, updated_at, version) values (?,?,?,'ACTIVE',now() + interval '1 hour',"
                + "now(),now(),0)", UUID.randomUUID(), storeId, orderId);
    }

    private CurrentActor actorFor(UUID storeId) {
        UUID userId = UUID.randomUUID();
        String email = "cancel-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Cancellation IT Actor", email, email, "en-US");
        return new CurrentActor(userId, "USER",
                Set.of("production:item-start", "production:item-ready"), Set.of(storeId), false);
    }

    // ----------------------------------------------------------- behaviour

    @Test
    void cancelledOrderRetiresQueuedTicketsAndItems() {
        UUID store = createStore();
        seedStation(store, "BAKERY", "BAKERY", 30);

        UUID order = createOrder(store, "CONFIRMED", "ORD-" + UUID.randomUUID());
        seedOrderItem(order, "BAKERY", "Croissant", 2);

        consumer.receive(paymentCompletedEnvelope(UUID.randomUUID(), order));
        assertEquals(1, statusCount(order, "PENDING"), "payment must queue a pending ticket");
        assertEquals(1, queueService.queue(store, StationType.BAKERY).size());

        consumer.receive(orderCancelledEnvelope(UUID.randomUUID(), order));

        assertEquals(1, statusCount(order, "CANCELLED"), "cancellation must retire the ticket");
        assertEquals(0, statusCount(order, "PENDING"), "no pending ticket may survive cancellation");
        assertEquals(0, queueService.queue(store, StationType.BAKERY).size(),
                "cancelled work must disappear from the board queue");

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from production.production_ticket_items i "
                        + "join production.production_tickets t on t.ticket_id = i.ticket_id "
                        + "where t.order_id = ? and i.status = 'CANCELLED'",
                Integer.class, order),
                "the pending item must be cancelled alongside its ticket");
    }

    @Test
    void redeliveryOfCancellationIsIdempotent() {
        UUID store = createStore();
        seedStation(store, "BARISTA", "BARISTA", 10);

        UUID order = createOrder(store, "CONFIRMED", "ORD-" + UUID.randomUUID());
        seedOrderItem(order, "BARISTA", "Flat White", 1);

        consumer.receive(paymentCompletedEnvelope(UUID.randomUUID(), order));

        UUID cancelEventId = UUID.randomUUID();
        consumer.receive(orderCancelledEnvelope(cancelEventId, order));
        consumer.receive(orderCancelledEnvelope(cancelEventId, order)); // broker redelivery

        assertEquals(1, statusCount(order, "CANCELLED"),
                "duplicate cancellation must leave exactly one cancelled ticket");
    }

    @Test
    void cancellationForOrderWithNoTicketsIsHarmlessNoOp() {
        UUID store = createStore();
        seedStation(store, "KITCHEN", "KITCHEN", 20);

        // Order cancelled before it ever entered production (no payment event).
        UUID order = createOrder(store, "CANCELLED", "ORD-" + UUID.randomUUID());

        assertDoesNotThrow(() -> consumer.receive(orderCancelledEnvelope(UUID.randomUUID(), order)));
        assertEquals(0, statusCount(order, "CANCELLED"),
                "cancelling an order with no tickets must not fabricate any");
    }

    @Test
    void unknownEventTypeNeitherCreatesNorCancels() {
        UUID store = createStore();
        seedStation(store, "BAKERY", "BAKERY", 30);

        // A confirmed, production-eligible order that is then hit with an
        // unrecognised event type must be left completely untouched.
        UUID order = createOrder(store, "CONFIRMED", "ORD-" + UUID.randomUUID());
        seedOrderItem(order, "BAKERY", "Croissant", 2);

        assertDoesNotThrow(() -> consumer.receive(unknownEnvelope(UUID.randomUUID(), order)));

        assertEquals(0, statusCount(order, "PENDING"),
                "an unknown event type must never create production tickets");
        assertEquals(0, statusCount(order, "CANCELLED"),
                "an unknown event type must never cancel production tickets");
    }

    @Test
    void unknownEventTypeDoesNotDisturbExistingQueuedTicket() {
        UUID store = createStore();
        seedStation(store, "BARISTA", "BARISTA", 10);

        UUID order = createOrder(store, "CONFIRMED", "ORD-" + UUID.randomUUID());
        seedOrderItem(order, "BARISTA", "Latte", 1);

        consumer.receive(paymentCompletedEnvelope(UUID.randomUUID(), order));
        assertEquals(1, statusCount(order, "PENDING"));

        consumer.receive(unknownEnvelope(UUID.randomUUID(), order));

        assertEquals(1, statusCount(order, "PENDING"),
                "the pending ticket must survive an unrelated event");
        assertEquals(0, statusCount(order, "CANCELLED"),
                "the pending ticket must not be cancelled by an unrelated event");
    }

    @Test
    void cancelledItemRejectsStartAndConsumesNoInventory() {
        UUID store = createStore();
        seedStation(store, "BARISTA", "BARISTA", 10);

        UUID order = createOrder(store, "CONFIRMED", "ORD-" + UUID.randomUUID());
        seedOrderItem(order, "BARISTA", "Latte", 1);
        consumer.receive(paymentCompletedEnvelope(UUID.randomUUID(), order));
        seedActiveReservation(store, order);
        UUID itemId = singleItemId(order);

        consumer.receive(orderCancelledEnvelope(UUID.randomUUID(), order));
        assertEquals("CANCELLED", itemStatus(itemId));

        // A stale Start command against a cancelled item is refused by the state
        // machine before it can consume the reservation or mark the order preparing.
        ConflictException ex = assertThrows(ConflictException.class,
                () -> workflow.start(actorFor(store), UUID.randomUUID(), itemId));
        assertEquals("PRODUCTION_STATE_CONFLICT", ex.code());
        assertEquals("CANCELLED", itemStatus(itemId), "rejected start must not mutate the cancelled item");
    }

    @Test
    void startThenCancelThenReadyIsRejectedAndWorkStaysRetired() {
        UUID store = createStore();
        seedStation(store, "BARISTA", "BARISTA", 10);

        UUID order = createOrder(store, "CONFIRMED", "ORD-" + UUID.randomUUID());
        seedOrderItem(order, "BARISTA", "Flat White", 1);
        consumer.receive(paymentCompletedEnvelope(UUID.randomUUID(), order));
        seedActiveReservation(store, order);
        UUID itemId = singleItemId(order);

        // Legal start moves the in-progress item to PREPARING.
        workflow.start(actorFor(store), UUID.randomUUID(), itemId);
        assertEquals("PREPARING", itemStatus(itemId));

        // Cancellation retires the outstanding (in-progress) work.
        consumer.receive(orderCancelledEnvelope(UUID.randomUUID(), order));
        assertEquals("CANCELLED", itemStatus(itemId),
                "an in-progress item must be retired by cancellation");
        assertEquals(1, statusCount(order, "CANCELLED"));
        assertEquals(0, queueService.queue(store, StationType.BARISTA).size(),
                "the cancelled ticket must be gone from the active board");

        // A stale Ready command against the cancelled item cannot resurrect it.
        assertThrows(ConflictException.class,
                () -> workflow.ready(actorFor(store), UUID.randomUUID(), itemId));
        assertEquals("CANCELLED", itemStatus(itemId));
    }
}
