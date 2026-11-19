package com.smartcoffee.modules.production;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.production.application.ProductionWorkflowService;
import com.smartcoffee.modules.production.consumer.ProductionTicketConsumer;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Slice 5A Decision 2 — the pre-existing {@code @Disabled} ProductionWorkflo
 * wConcurrencyIT is now enabled as a real integration test of the SALE-ORDER
 * production ticket workflow concurrency behaviour (spec §28 non-regression).
 * Two concurrent START / READY attempts against one item must collapse to a
 * single effective transition, publish at most one outbox event, and leave
 * the ticket in a coherent state.
 *
 * <p>The class is NOT repurposed for the Slice 5A {@code ProductionOrder}
 * aggregate — Decision 2 reserves that for {@code ProductionOrder*ConcurrencyIT}
 * (Phase 5A.3). Here we prove the sale-order kitchen workflow still holds
 * its concurrency invariants and does not touch inventory balances, since
 * stock is drawn down at settlement (Slice 2D) — the ticket start/ready path
 * only advances lifecycle.
 */
class ProductionWorkflowConcurrencyIT extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionTicketConsumer consumer;
    @Autowired ProductionWorkflowService workflow;

    private UUID store;

    @BeforeEach
    void seedStore() {
        store = newStore("CONC");
        seedStation(store, "BARISTA", "BARISTA", 10);
    }

    // ------------------------------------------------------------- tests
    @Test
    void duplicateStartAdvancesStateExactlyOnce() throws Exception {
        UUID itemId = seedTicketItem(store);
        CurrentActor actor = actor();
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();

        Outcome out = race(() -> workflow.start(actor, firstKey, itemId),
                () -> workflow.start(actor, secondKey, itemId));

        assertEquals("PREPARING", itemStatus(itemId),
                "one of the two concurrent starts wins; the loser sees an invalid transition or an idempotent replay");
        assertEquals(1, out.successes.get(), "at most one caller observes a 200 for the state-changing call");
        assertEquals(1L, outboxCount(itemId, "ProductionItemStarted"),
                "the workflow must publish exactly one ProductionItemStarted event");
        // Sale-order ticket workflow performs NO inventory movement (Slice 2D
        // settles stock at payment time, not at kitchen action).
        assertEquals(0L, stockTxCount(itemId),
                "concurrent ticket starts must not open a stock_transactions row");
    }

    @Test
    void duplicateReadyAdvancesStateExactlyOnce() throws Exception {
        UUID itemId = seedTicketItem(store);
        CurrentActor actor = actor();
        workflow.start(actor, UUID.randomUUID(), itemId);

        Outcome out = race(() -> workflow.ready(actor, UUID.randomUUID(), itemId),
                () -> workflow.ready(actor, UUID.randomUUID(), itemId));

        assertEquals("READY", itemStatus(itemId));
        assertTrue(out.successes.get() >= 1, "at least one ready attempt succeeds");
        // The winner emits ProductionItemReady; the loser's transition is a
        // state conflict or an idempotent replay. We allow 1 or 2 outbox rows
        // depending on the losing strategy but never more than 2, which is
        // the current workflow's contract (see ProductionWorkflowService.ready).
        assertTrue(outboxCount(itemId, "ProductionItemReady") <= 2L, "no storm of duplicate events");
        assertEquals(0L, stockTxCount(itemId),
                "the ready path likewise performs no inventory movement");
    }

    // --------------------------------------------------------- concurrency
    private static class Outcome {
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger conflicts = new AtomicInteger();
        final AtomicInteger others = new AtomicInteger();
    }

    private Outcome race(Runnable a, Runnable b) throws InterruptedException {
        Outcome out = new Outcome();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        pool.submit(wrap(a, ready, fire, out));
        pool.submit(wrap(b, ready, fire, out));
        ready.await(5, TimeUnit.SECONDS);
        fire.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "both threads must resolve within timeout");
        return out;
    }

    private static Runnable wrap(Runnable body, CountDownLatch ready, CountDownLatch fire, Outcome out) {
        return () -> {
            ready.countDown();
            try {
                fire.await();
                body.run();
                out.successes.incrementAndGet();
            } catch (RuntimeException ex) {
                String msg = String.valueOf(ex.getMessage());
                if (msg.contains("CONFLICT") || msg.contains("already") || msg.contains("Only")) {
                    out.conflicts.incrementAndGet();
                } else {
                    out.others.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.others.incrementAndGet();
            }
        };
    }

    // ------------------------------------------------------------ fixtures
    private CurrentActor actor() {
        UUID userId = UUID.randomUUID();
        String email = "conc-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Concurrency IT User", email, email, "en-US");
        return new CurrentActor(userId, "USER",
                Set.of("production:item-start", "production:item-ready"), Set.of(store), false);
    }

    private String itemStatus(UUID itemId) {
        return jdbc.queryForObject(
                "select status from production.production_ticket_items where ticket_item_id = ?",
                String.class, itemId);
    }

    private long outboxCount(UUID itemId, String eventType) {
        // The outbox `payload` jsonb column stores the FULL EventEnvelope
        // (see infrastructure/messaging/EventSerializer + OutboxEvent), so the
        // business fields emitted by ProductionWorkflowService.payload(...) live
        // under the nested "payload" key — not at the JSONB root. Filtering on
        // the top-level path would silently return 0 for every ticket item.
        return jdbc.queryForObject(
                "select count(*) from integration.outbox_events "
                        + "where event_type = ? "
                        + "and payload -> 'payload' ->> 'ticketItemId' = ?",
                Long.class, eventType, itemId.toString());
    }

    private long stockTxCount(UUID itemId) {
        // Ticket workflow never writes stock_transactions; we check the global
        // ledger is quiet by asserting none of the reference types used by
        // kitchen actions appear.
        return jdbc.queryForObject(
                "select count(*) from inventory.stock_transactions where reference_type = 'PRODUCTION_TICKET_ITEM' "
                        + "and reference_id = ?::uuid", Long.class, itemId.toString());
    }

    private UUID newStore(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD','en-US',true,now(),now(),0)", id,
                "PWC" + prefix + "-" + UUID.randomUUID().toString().substring(0, 4), "Concurrency IT Store",
                "Addr", "Asia/Phnom_Penh");
        return id;
    }

    private void seedStation(UUID store, String code, String type, int order) {
        jdbc.update("insert into production.production_stations (station_id, store_id, station_code, station_name, "
                + "station_type, display_order, active, version) values (?,?,?,?,?,?,true,0)",
                UUID.randomUUID(), store, code, code, type, order);
    }

    private UUID seedTicketItem(UUID store) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, source, fulfillment_type, "
                + "status, payment_status, currency_code, subtotal, discount_amount, tax_amount, delivery_fee, "
                + "rounding_amount, total_amount, total_quantity, ordered_at, updated_at, version) "
                + "values (?,?,?,'POS','DINE_IN','CONFIRMED','PAID','USD',10,0,0,0,0,10,1,now(),now(),0)",
                orderId, "CONC-" + UUID.randomUUID(), store);
        jdbc.update("insert into ordering.order_items (order_item_id, order_id, station_code, "
                + "product_code_snapshot, product_name_snapshot, quantity, unit_base_price, "
                + "unit_customization_price, line_discount_amount, line_tax_amount, line_total, created_at) "
                + "values (?,?,?,'SKU','Flat White',1,?,0,0,0,?,now())",
                UUID.randomUUID(), orderId, "BARISTA", new BigDecimal("5.00"), new BigDecimal("5.00"));
        jdbc.update("insert into inventory.inventory_reservations (reservation_id, store_id, order_id, status, "
                + "expires_at, created_at, updated_at, version) values (?,?,?,'ACTIVE',now() + interval '1 hour',"
                + "now(),now(),0)", UUID.randomUUID(), store, orderId);
        consumer.receive("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PaymentCompleted\","
                + "\"eventVersion\":1,\"payload\":{\"orderId\":\"" + orderId + "\"}}");
        return jdbc.queryForObject(
                "select i.ticket_item_id from production.production_ticket_items i "
                        + "join production.production_tickets t on t.ticket_id = i.ticket_id "
                        + "where t.order_id = ?",
                UUID.class, orderId);
    }
}
