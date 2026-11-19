package com.smartcoffee.modules.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.modules.cart.api.AddCartItemRequest;
import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.cart.application.CartService;
import com.smartcoffee.modules.ordering.api.CreateOrderRequest;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.application.OrderCheckoutService;
import com.smartcoffee.modules.payment.api.CashPaymentRequest;
import com.smartcoffee.modules.payment.api.PaymentResponse;
import com.smartcoffee.modules.payment.api.ReceiptResponse;
import com.smartcoffee.modules.payment.application.PaymentApplicationService;
import com.smartcoffee.modules.payment.application.ReceiptProcessor;
import com.smartcoffee.modules.payment.application.ReceiptQueryService;
import com.smartcoffee.modules.payment.application.ReceiptRecoveryService;
import com.smartcoffee.modules.payment.consumer.ReceiptIssuanceConsumer;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.CreateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.api.request.RecipeItemRequest;
import com.smartcoffee.modules.recipe.application.RecipeAssignmentService;
import com.smartcoffee.modules.recipe.application.RecipeCommandService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Receipt processing corrective slice. The verified defect: an authoritative
 * settlement persists the immutable Receipt snapshot and appends a durable
 * {@code ReceiptRequested} outbox event, but nothing consumes it, so the row
 * stays PENDING forever and no document is ever produced.
 */
class ReceiptIssuanceIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");
    private static final String STOCK_ON_HAND = "1000";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired CartService carts;
    @Autowired OrderCheckoutService checkouts;
    @Autowired PaymentApplicationService payments;
    @Autowired RecipeCommandService recipeCommands;
    @Autowired RecipeAssignmentService assignment;
    @Autowired ObjectStorageService storage;
    @Autowired ReceiptIssuanceConsumer consumer;
    @Autowired ReceiptProcessor processor;
    @Autowired ReceiptRecoveryService recovery;
    @Autowired ReceiptQueryService receiptQueries;

    private CurrentActor admin;
    private UUID storeId;
    private UUID userId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        admin = seedActor(SUPER_ADMIN);
        storeId = seedStore();
        userId = seedCustomer();
        UUID beans = seedIngredient("RCPT_BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("RCPT_MILK_" + suffix(), MILLILITER);
        UUID cup = seedIngredient("RCPT_CUP_" + suffix(), PIECE);
        for (UUID ingredient : List.of(beans, milk, cup)) {
            seedBalance(ingredient, STOCK_ON_HAND);
        }
        UUID product = seedProduct("RCPT_" + suffix());
        offerProduct(product);
        assignRecipe(product, List.of(item(beans, "18", 0), item(milk, "180", 1), item(cup, "1", 2)));

        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, product, null, 1, null, List.of()));
        OrderResponse order = checkouts.checkout(userId, UUID.randomUUID(),
                new CreateOrderRequest(cart.cartId(), "PICKUP", null));
        orderId = order.orderId();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void settlementIssuesReceiptAutomaticallyWithoutManualAction() throws Exception {
        PaymentResponse paid = payments.cash(userId, UUID.randomUUID(),
                new CashPaymentRequest(orderId, storeId, dec("20"), "USD", null));
        assertEquals("PAID", paid.status(), "the payment must be authoritatively settled");

        // Exactly one receipt snapshot exists for this settlement.
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from payment.receipts where order_id = ?",
                Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from integration.outbox_events "
                        + "where event_type = 'ReceiptRequested' and aggregate_id = ?",
                Integer.class, paid.paymentId()),
                "settlement must append exactly one durable receipt request");

        UUID receiptId = jdbc.queryForObject(
                "select receipt_id from payment.receipts where order_id = ?", UUID.class, orderId);

        // Verified defect: nothing consumed the request, so the receipt stayed
        // PENDING and the configured output was never produced.
        awaitStatus(receiptId, "ISSUED");

        var row = jdbc.queryForMap("select status, object_key, issued_at, mime_type, size_bytes, checksum, "
                + "failure_reason, attempts from payment.receipts where receipt_id = ?", receiptId);
        assertEquals("ISSUED", row.get("status"));
        assertNotNull(row.get("issued_at"));
        assertNull(row.get("failure_reason"));
        assertEquals("text/plain", row.get("mime_type"));
        assertTrue(((Number) row.get("size_bytes")).longValue() > 0);
        assertNotNull(row.get("checksum"));
        assertTrue(((Number) row.get("attempts")).intValue() >= 1);

        String objectKey = "receipts/" + receiptId + ".txt";
        assertEquals(objectKey, row.get("object_key"));
        assertTrue(storage.exists(objectKey), "the receipt artifact must exist exactly once");
        String document = new String(storage.download(objectKey), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(document.startsWith("SMART Coffee & Bakery Receipt"), document);

        // The immutable financial snapshot is unchanged by issuance.
        var snapshot = jdbc.queryForObject(
                "select receipt_snapshot from payment.receipts where order_id = ?",
                String.class, orderId);
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("\"orderNumber\""), snapshot);
        assertTrue(snapshot.contains("\"total\""), snapshot);

        // Durable consumption evidence: the request was published and processed.
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "select status from integration.outbox_events "
                        + "where event_type = 'ReceiptRequested' and aggregate_id = ?",
                String.class, paid.paymentId()));
        UUID eventId = jdbc.queryForObject(
                "select event_id from integration.outbox_events "
                        + "where event_type = 'ReceiptRequested' and aggregate_id = ?",
                UUID.class, paid.paymentId());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from integration.consumer_inbox "
                        + "where consumer_name = 'receipt-issuance' and event_id = ? and status = 'PROCESSED'",
                Integer.class, eventId));
    }

    @Test
    void consumerReplayAndProcessorReplayDoNotDuplicateOutput() throws Exception {
        PaymentResponse paid = settle();
        UUID receiptId = receiptIdFor(paid.paymentId());
        awaitStatus(receiptId, "ISSUED");
        var before = state(receiptId);

        // Broker redelivery of the very same event: the inbox deduplicates it.
        String raw = jdbc.queryForObject(
                "select payload::text from integration.outbox_events where event_type = 'ReceiptRequested' "
                        + "and aggregate_id = ?", String.class, paid.paymentId());
        consumer.receive(raw);
        consumer.receive(raw);

        // A second, distinct request for the same receipt is a processor no-op.
        assertEquals(ReceiptProcessor.Outcome.ALREADY_ISSUED, processor.process(receiptId));

        assertEquals(before.get("issued_at"), state(receiptId).get("issued_at"));
        assertEquals(1, ((Number) state(receiptId).get("attempts")).intValue());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from payment.receipts where payment_id = ?", Integer.class, paid.paymentId()));
        assertTrue(storage.exists("receipts/" + receiptId + ".txt"));
    }

    @Test
    void concurrentProcessorsIssueExactlyOnce() throws Exception {
        Seeded seeded = seedReceipt("PENDING", "{\"total\":\"11.00\"}", Instant.now(), 0, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<ReceiptProcessor.Outcome> one = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return processor.process(seeded.receiptId());
            });
            Future<ReceiptProcessor.Outcome> two = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return processor.process(seeded.receiptId());
            });

            List<ReceiptProcessor.Outcome> outcomes = List.of(one.get(30, TimeUnit.SECONDS),
                    two.get(30, TimeUnit.SECONDS));
            assertTrue(outcomes.contains(ReceiptProcessor.Outcome.ISSUED), outcomes.toString());
            assertTrue(outcomes.contains(ReceiptProcessor.Outcome.ALREADY_ISSUED), outcomes.toString());
        } finally {
            pool.shutdownNow();
        }

        var row = state(seeded.receiptId());
        assertEquals("ISSUED", row.get("status"));
        assertEquals(1, ((Number) row.get("attempts")).intValue(), "exactly one attempt performed the work");
        assertTrue(storage.exists("receipts/" + seeded.receiptId() + ".txt"));
    }

    @Test
    void recoveryIssuesStrandedReceiptAndHonoursAttemptCeiling() {
        Instant old = Instant.now().minus(10, ChronoUnit.MINUTES);
        Seeded stranded = seedReceipt("PENDING", "{\"total\":\"11.00\"}", old, 0, null, null);
        Seeded exhausted = seedReceipt("FAILED", "{\"total\":\"11.00\"}", old, 5, old, "RECEIPT_STORAGE_FAILED");

        recovery.run();

        assertEquals("ISSUED", state(stranded.receiptId()).get("status"));
        assertTrue(storage.exists("receipts/" + stranded.receiptId() + ".txt"));
        assertEquals("FAILED", state(exhausted.receiptId()).get("status"),
                "a receipt at the attempt ceiling must not be retried indefinitely");
    }

    @Test
    void recoveryClaimIsMultiNodeSafe() throws Exception {
        Seeded seeded = seedReceipt("PENDING", "{\"total\":\"11.00\"}",
                Instant.now().minus(5, ChronoUnit.MINUTES), 0, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<?> one = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                recovery.run();
                return null;
            });
            Future<?> two = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                recovery.run();
                return null;
            });
            one.get(60, TimeUnit.SECONDS);
            two.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        var row = state(seeded.receiptId());
        assertEquals("ISSUED", row.get("status"));
        assertEquals(1, ((Number) row.get("attempts")).intValue(), "only one node may issue");
        assertTrue(storage.exists("receipts/" + seeded.receiptId() + ".txt"));
    }

    @Test
    void pendingReceiptIsReportedHonestlyWithoutDownload() {
        seedReceipt("PENDING", "{\"total\":\"11.00\"}", Instant.now(), 0, null, null);
        authenticateCustomer();

        List<ReceiptResponse> receipts = receiptQueries.receipt(actors.require(), orderId);

        assertEquals(1, receipts.size());
        ReceiptResponse pending = receipts.get(0);
        assertEquals("PENDING", pending.status());
        assertNull(pending.downloadUrl());
        assertNull(pending.failureReason());
        assertEquals(0, new BigDecimal("11.00").compareTo(
                new BigDecimal(pending.snapshot().path("total").asString())));
    }

    @Test
    void issuedReceiptApiReturnsSafeDownloadAndImmutableSnapshots() throws Exception {
        PaymentResponse paid = settle();
        UUID receiptId = receiptIdFor(paid.paymentId());
        awaitStatus(receiptId, "ISSUED");
        authenticateCustomer();

        List<ReceiptResponse> receipts = receiptQueries.receipt(actors.require(), orderId);

        assertEquals(1, receipts.size());
        ReceiptResponse issued = receipts.get(0);
        assertEquals("ISSUED", issued.status());
        assertNotNull(issued.downloadUrl());
        assertTrue(issued.downloadUrl().startsWith("http"));
        // Short-lived signed URL: it carries a signature, never the secret used to compute it.
        assertTrue(issued.downloadUrl().contains("X-Amz-Signature"));
        assertFalse(issued.downloadUrl().toLowerCase().contains("secretkey"));
        assertFalse(issued.downloadUrl().toLowerCase().contains("password"));
        assertEquals("text/plain", issued.mimeType());
        assertTrue(issued.sizeBytes() > 0);
        assertNotNull(issued.checksum());
        assertEquals(0, new BigDecimal("11.00").compareTo(
                new BigDecimal(issued.snapshot().path("total").asString())));
        assertTrue(issued.snapshot().path("promotion").isMissingNode(),
                "an unpromoted order must not gain a promotion snapshot");
        assertFalse(issued.toString().contains("objectKey"), "internal storage key is not exposed");
    }

    // ------------------------------------------------------------- helpers

    private PaymentResponse settle() {
        PaymentResponse paid = payments.cash(userId, UUID.randomUUID(),
                new CashPaymentRequest(orderId, storeId, dec("20"), "USD", null));
        assertEquals("PAID", paid.status());
        return paid;
    }

    private UUID receiptIdFor(UUID paymentId) {
        return jdbc.queryForObject(
                "select receipt_id from payment.receipts where payment_id = ?", UUID.class, paymentId);
    }

    private java.util.Map<String, Object> state(UUID receiptId) {
        return jdbc.queryForMap("select status, issued_at, object_key, attempts, failure_reason "
                + "from payment.receipts where receipt_id = ?", receiptId);
    }

    private record Seeded(UUID paymentId, UUID receiptId) {
    }

    private Seeded seedReceipt(String status, String snapshot, Instant createdAt, int attempts,
            Instant lastAttemptAt, String failureReason) {
        UUID paymentId = UUID.randomUUID();
        // Synthetic payment: PENDING keeps the one-PAID-per-order constraint out
        // of the way (issuance reads only the receipt snapshot).
        jdbc.update("insert into payment.payments (payment_id, payment_reference, order_id, store_id, "
                + "payment_method_id, status, currency_code, amount, created_at, updated_at) "
                + "values (?::uuid, ?, ?::uuid, ?::uuid, '80000000-0000-0000-0000-000000000001', 'PENDING', 'USD', "
                + "11, now(), now())", paymentId.toString(), "RCPREF" + paymentId, orderId.toString(),
                storeId.toString());
        UUID receiptId = UUID.randomUUID();
        jdbc.update("insert into payment.receipts (receipt_id, receipt_number, payment_id, order_id, store_id, status, "
                + "receipt_snapshot, created_at, attempts, last_attempt_at, failure_reason) "
                + "values (?::uuid, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?::jsonb, ?, ?, ?, ?)",
                receiptId.toString(), "RCP" + receiptId, paymentId.toString(), orderId.toString(),
                storeId.toString(), status, snapshot, java.sql.Timestamp.from(createdAt), attempts,
                lastAttemptAt == null ? null : java.sql.Timestamp.from(lastAttemptAt), failureReason);
        return new Seeded(paymentId, receiptId);
    }

    private void authenticateCustomer() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void awaitStatus(UUID receiptId, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = jdbc.queryForObject(
                    "select status from payment.receipts where receipt_id = ?", String.class, receiptId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        fail("receipt did not reach " + expected + " within 60s (last status " + status + ")");
    }

    // ------------------------------------------------------------- fixtures

    private UUID assignRecipe(UUID productId, List<RecipeItemRequest> items) {
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "receipt " + suffix(), items));
        RecipeVersionResponse active = recipeCommands.activate(admin, draft.recipeVersionId(), draft.version());
        assignment.assignProduct(admin, productId, new AssignProductRecipeRequest(active.recipeVersionId(),
                jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class,
                        productId)));
        return active.recipeVersionId();
    }

    private void offerProduct(UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", storeId, productId);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Receipt Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "RC" + UUID.randomUUID().toString().substring(0, 7).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "receipt-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Receipt Customer", email, email, "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "receipt-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Receipt Admin", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), id, UUID.fromString(roleId), null,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        return actors.require();
    }

    private UUID seedProduct(String code) {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, false, true, now(), now(), 0)",
                id, code, category, "IT " + code, dec("10"));
        return id;
    }

    private UUID seedIngredient(String code, UUID baseUnit) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, "
                + "base_unit_id, standard_cost, track_inventory, track_batches, active, created_at, updated_at, "
                + "version) values (?,?,?,?,0,true,false,true,now(),now(),0)", id, code, code, baseUnit);
        return id;
    }

    private void seedBalance(UUID ingredient, String current) {
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id, current_stock, "
                + "reserved_stock, minimum_stock, average_unit_cost) values (?,?,?,0,0,0.025)", storeId, ingredient,
                dec(current));
    }

    private RecipeItemRequest item(UUID ingredient, String quantity, int order) {
        return new RecipeItemRequest(ingredient, dec(quantity), BigDecimal.ZERO, null,
                com.smartcoffee.modules.recipe.domain.RecipeScalingRule.FIXED, order, false, null);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
