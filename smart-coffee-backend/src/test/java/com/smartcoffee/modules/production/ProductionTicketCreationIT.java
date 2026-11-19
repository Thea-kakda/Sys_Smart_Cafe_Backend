package com.smartcoffee.modules.production;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.modules.production.api.ProductionTicketResponse;
import com.smartcoffee.modules.production.application.ProductionQueueQueryService;
import com.smartcoffee.modules.production.consumer.ProductionTicketConsumer;
import com.smartcoffee.modules.production.domain.StationType;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checkpoint 13 — production ticket creation wiring.
 *
 * <p>{@code ProductionTicketService.createForOrder} was previously dead code:
 * nothing consumed the confirmed-payment signal, so the KDS/BDS queues could
 * never receive work. This test drives the real
 * {@link ProductionTicketConsumer} exactly as the broker would (a
 * {@code payment.completed.v1} envelope) against the migrated database and
 * proves the end-to-end data flow the boards depend on:</p>
 *
 * <ul>
 *   <li>a confirmed order fans out into one ticket per routed station;</li>
 *   <li>items are grouped by their authoritative {@code station_code};</li>
 *   <li>the queue read service surfaces the created tickets;</li>
 *   <li>redelivery of the same event is idempotent (no duplicate tickets);</li>
 *   <li>a non-confirmed order produces nothing and is not marked processed.</li>
 * </ul>
 */
class ProductionTicketCreationIT extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionTicketConsumer consumer;
    @Autowired ProductionQueueQueryService queueService;

    // ------------------------------------------------------------- fixtures

    private UUID createStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD','en-US',true,now(),now(),0)",
                id, "PT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "Production IT Store",
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

    private int ticketCount(UUID orderId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from production.production_tickets where order_id = ?",
                Integer.class, orderId);
        return n == null ? 0 : n;
    }

    // ----------------------------------------------------------- behaviour

    @Test
    void confirmedOrderFansOutIntoStationTicketsAndQueue() {
        UUID store = createStore();
        seedStation(store, "BAKERY", "BAKERY", 30);
        seedStation(store, "KITCHEN", "KITCHEN", 20);

        String orderNumber = "ORD-" + UUID.randomUUID();
        UUID order = createOrder(store, "CONFIRMED", orderNumber);
        seedOrderItem(order, "BAKERY", "Croissant", 2);
        seedOrderItem(order, "KITCHEN", "Club Sandwich", 1);

        consumer.receive(paymentCompletedEnvelope(UUID.randomUUID(), order));

        // One ticket per routed station.
        assertEquals(2, ticketCount(order),
                "confirmed order with two distinct stations must create two tickets");

        List<String> numbers = jdbc.queryForList(
                "select ticket_number from production.production_tickets where order_id = ? order by ticket_number",
                String.class, order);
        assertEquals(List.of("BAKERY-" + orderNumber, "KITCHEN-" + orderNumber), numbers,
                "ticket numbers must carry the station code and order number");

        // Each ticket groups its own item and starts PENDING.
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from production.production_ticket_items i "
                        + "join production.production_tickets t on t.ticket_id = i.ticket_id "
                        + "where t.order_id = ? and i.product_name_snapshot = 'Croissant' and i.status = 'PENDING'",
                Integer.class, order));

        // The authoritative queue read surfaces the bakery work for the BDS board.
        List<ProductionTicketResponse> bakery = queueService.queue(store, StationType.BAKERY);
        assertEquals(1, bakery.size());
        assertEquals(order, bakery.get(0).orderId());
        assertEquals("BAKERY", bakery.get(0).stationType());
        assertEquals("PENDING", bakery.get(0).status());
        assertEquals(1, bakery.get(0).items().size());
        assertEquals("Croissant", bakery.get(0).items().get(0).productName());

        // And kitchen work for the KDS board.
        List<ProductionTicketResponse> kitchen = queueService.queue(store, StationType.KITCHEN);
        assertEquals(1, kitchen.size());
        assertEquals("Club Sandwich", kitchen.get(0).items().get(0).productName());
    }

    @Test
    void redeliveryOfSameEventIsIdempotent() {
        UUID store = createStore();
        seedStation(store, "BARISTA", "BARISTA", 10);

        UUID order = createOrder(store, "CONFIRMED", "ORD-" + UUID.randomUUID());
        seedOrderItem(order, "BARISTA", "Flat White", 1);

        UUID eventId = UUID.randomUUID();
        consumer.receive(paymentCompletedEnvelope(eventId, order));
        consumer.receive(paymentCompletedEnvelope(eventId, order)); // broker redelivery

        assertEquals(1, ticketCount(order),
                "duplicate delivery of the same event must not create duplicate tickets");
    }

    @Test
    void unconfirmedOrderCreatesNothingAndIsNotMarkedProcessed() {
        UUID store = createStore();
        seedStation(store, "BAKERY", "BAKERY", 30);

        UUID order = createOrder(store, "AWAITING_PAYMENT", "ORD-" + UUID.randomUUID());
        seedOrderItem(order, "BAKERY", "Bagel", 1);

        UUID eventId = UUID.randomUUID();
        assertThrows(RuntimeException.class,
                () -> consumer.receive(paymentCompletedEnvelope(eventId, order)));

        assertEquals(0, ticketCount(order),
                "no production ticket may be created for a non-confirmed order");
        assertEquals(0,
                jdbc.queryForObject(
                        "select count(*) from integration.consumer_inbox where consumer_name = 'production' "
                                + "and event_id = ? and status = 'PROCESSED'",
                        Integer.class, eventId),
                "failed processing must roll back the inbox marker so the event can be retried");
    }
}
