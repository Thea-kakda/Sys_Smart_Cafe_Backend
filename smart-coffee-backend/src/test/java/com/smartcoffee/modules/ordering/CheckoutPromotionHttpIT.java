package com.smartcoffee.modules.ordering;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.modules.auth.api.RegisterRequest;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase B2 §13 HTTP contract: checkout is the authoritative place a Promotion
 * code is applied. The client submits a code (optionally with hostile extra
 * fields), and the backend rebuilds everything else: eligibility, eligible
 * lines, discount, tax and totals. Rejections leave no order, no redemption and
 * no usage increment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutPromotionHttpIT extends IntegrationTestBase {

    private static final String ORDERS = "/api/v1/orders";
    private static final String CART_ITEMS = "/api/v1/carts/current/items";
    private static final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private UUID storeId;
    private UUID productId;

    @BeforeEach
    void seedCatalog() {
        storeId = seedStore();
        productId = seedProduct("PROMO_LATTE_" + suffix(), "10.00");
        offer(storeId, productId);
    }

    @Test
    void noCodeKeepsTheOrderUnpromoted() throws Exception {
        Session user = register();
        String cartId = addToCart(user.token, productId);

        Resp order = checkout(user.token, UUID.randomUUID(), cartId, null);

        assertEquals(201, order.status(), order.body());
        JsonNode data = order.data();
        assertTrue(data.path("promotionCode").isNull());
        assertEquals(0, dec("0").compareTo(new BigDecimal(data.path("discountAmount").asText())));
        assertEquals(0, dec("11.00").compareTo(new BigDecimal(data.path("totalAmount").asText())));
        assertEquals(0L, count("select count(*) from promotion.promotion_redemptions where store_id=?", storeId));
    }

    @Test
    void validCodeAppliesAuthoritativeDiscountAndSnapshotsIt() throws Exception {
        UUID promotionId = seedPromotion("SAVE10", "PERCENTAGE", "10", "5", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, null, null, null);
        Session user = register();
        String cartId = addToCart(user.token, productId);

        String hostile = "{\"cartId\":\"" + cartId + "\",\"fulfillmentType\":\"PICKUP\",\"promotionCode\":\" save10 \""
                + ",\"customerId\":\"" + UUID.randomUUID() + "\",\"promotionId\":\"" + UUID.randomUUID()
                + "\",\"subtotal\":0.01,\"eligibleSubtotal\":0.01,\"discountAmount\":9.99,\"taxAmount\":0,"
                + "\"totalAmount\":0.01,\"storeId\":\"" + UUID.randomUUID() + "\"}";
        Resp order = checkoutRaw(user.token, UUID.randomUUID(), hostile);

        assertEquals(201, order.status(), order.body());
        JsonNode data = order.data();
        assertEquals("SAVE10", data.path("promotionCode").asText());
        assertEquals("Welcome", data.path("promotionName").asText());
        assertEquals(0, dec("10.00").compareTo(new BigDecimal(data.path("subtotal").asText())));
        assertEquals(0, dec("1.00").compareTo(new BigDecimal(data.path("discountAmount").asText())));
        assertEquals(0, dec("0.90").compareTo(new BigDecimal(data.path("taxAmount").asText())));
        assertEquals(0, dec("9.90").compareTo(new BigDecimal(data.path("totalAmount").asText())));
        assertEquals(0, dec("10.00").compareTo(new BigDecimal(data.path("promotionEligibleSubtotal").asText())));
        assertEquals(promotionId.toString(), data.path("promotionId").asText());
        JsonNode line = data.path("items").get(0);
        assertEquals(0, dec("10.00").compareTo(new BigDecimal(line.path("lineSubtotal").asText())));
        assertEquals(0, dec("1.00").compareTo(new BigDecimal(line.path("lineDiscountAmount").asText())));
        assertEquals(0, dec("0.90").compareTo(new BigDecimal(line.path("lineTaxAmount").asText())));
        assertEquals(0, dec("9.90").compareTo(new BigDecimal(line.path("lineTotal").asText())));

        assertEquals("SAVE10", jdbc.queryForObject(
                "select promotion_code_snapshot from ordering.orders where order_id=?", String.class,
                UUID.fromString(data.path("orderId").asText())));
        assertEquals(0, dec("1.00").compareTo(jdbc.queryForObject(
                "select promotion_discount_amount from ordering.orders where order_id=?", BigDecimal.class,
                UUID.fromString(data.path("orderId").asText()))));
        assertEquals("RESERVED", redemptionColumn("status"));
        assertEquals(0, dec("10.00").compareTo(redemptionDecimal("eligible_subtotal")));
        assertEquals("SAVE10", redemptionColumn("promotion_code_snapshot"));
        assertNotNull(redemptionColumn("expires_at"));
        assertNotNull(redemptionColumn("idempotency_key"));
        assertEquals(1, jdbc.queryForObject("select usage_count from promotion.promotions where promotion_id=?",
                Integer.class, promotionId));
        assertEquals(1L, count("select count(*) from integration.outbox_events where event_type='PromotionReserved' "
                + "and aggregate_id=?", UUID.fromString(data.path("orderId").asText())));
    }

    @Test
    void discountAndTaxApplyOnlyToTargetedLines() throws Exception {
        UUID other = seedProduct("OTHER_" + suffix(), "5.00");
        offer(storeId, other);
        UUID promotionId = seedPromotion("TARGET10", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, null, productId, null);
        Session user = register();
        String cartId = addToCart(user.token, productId);
        addToCart(user.token, other, cartId);

        Resp order = checkout(user.token, UUID.randomUUID(), cartId, "TARGET10");

        assertEquals(201, order.status(), order.body());
        JsonNode data = order.data();
        assertEquals(0, dec("15.00").compareTo(new BigDecimal(data.path("subtotal").asText())));
        assertEquals(0, dec("1.00").compareTo(new BigDecimal(data.path("discountAmount").asText())));
        assertEquals(0, dec("1.40").compareTo(new BigDecimal(data.path("taxAmount").asText())));
        assertEquals(0, dec("15.40").compareTo(new BigDecimal(data.path("totalAmount").asText())));
        assertEquals(0, dec("10.00").compareTo(new BigDecimal(data.path("promotionEligibleSubtotal").asText())));
        assertEquals(2, data.path("items").size());
        BigDecimal discounts = jdbc.queryForObject(
                "select coalesce(sum(line_discount_amount),0) from ordering.order_items where order_id=?",
                BigDecimal.class, UUID.fromString(data.path("orderId").asText()));
        assertEquals(0, dec("1.00").compareTo(discounts));
        assertEquals(promotionId.toString(), data.path("promotionId").asText());
    }

    @Test
    void unknownCodeIsRejectedWithoutSideEffects() throws Exception {
        Session user = register();
        String cartId = addToCart(user.token, productId);

        Resp rejected = checkout(user.token, UUID.randomUUID(), cartId, "NOPE");

        assertEquals(409, rejected.status(), rejected.body());
        assertEquals("PROMOTION_NOT_FOUND", rejected.errorCode());
        assertNoOrderOrRedemption();
        assertEquals("ACTIVE", jdbc.queryForObject("select status from cart.carts where cart_id=?", String.class,
                UUID.fromString(cartId)));
    }

    @Test
    void pausedPromotionIsRejected() throws Exception {
        seedPromotion("PAUSED10", "PERCENTAGE", "10", "0", null, "USD", "PAUSED",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, null, null, null);
        assertRejected("PAUSED10", "PROMOTION_NOT_ACTIVE");
    }

    @Test
    void notYetStartedPromotionIsRejected() throws Exception {
        seedPromotion("FUTURE10", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() + interval '1 day'", "now() + interval '2 days'", 100, 5, null, null, null);
        assertRejected("FUTURE10", "PROMOTION_NOT_STARTED");
    }

    @Test
    void endedPromotionIsRejected() throws Exception {
        seedPromotion("ENDED10", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() - interval '2 days'", "now() - interval '1 day'", 100, 5, null, null, null);
        assertRejected("ENDED10", "PROMOTION_EXPIRED");
    }

    @Test
    void promotionForAnotherStoreIsRejected() throws Exception {
        UUID otherStore = seedStore();
        seedPromotion("STORE10", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, otherStore, null, null);
        assertRejected("STORE10", "PROMOTION_STORE_NOT_ELIGIBLE");
    }

    @Test
    void promotionInAnotherCurrencyIsRejected() throws Exception {
        seedPromotion("KHR10", "PERCENTAGE", "10", "0", null, "KHR", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, null, null, null);
        assertRejected("KHR10", "PROMOTION_CURRENCY_MISMATCH");
    }

    @Test
    void promotionWithNoEligibleLineIsRejected() throws Exception {
        UUID other = seedProduct("UNRELATED_" + suffix(), "5.00");
        offer(storeId, other);
        seedPromotion("ONLYOTHER", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, null, other, null);
        assertRejected("ONLYOTHER", "PROMOTION_NO_ELIGIBLE_ITEMS");
    }

    @Test
    void minimumNotMetIsRejected() throws Exception {
        seedPromotion("MIN100", "FIXED_AMOUNT", "2", "100", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, null, null, null);
        assertRejected("MIN100", "PROMOTION_MINIMUM_NOT_MET");
    }

    @Test
    void globalUsageLimitIsRejectedAndUnchanged() throws Exception {
        UUID promotionId = seedPromotion("LASTONE", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 1, 1, null, null, null);
        jdbc.update("update promotion.promotions set usage_count=1 where promotion_id=?", promotionId);
        Session user = register();
        String cartId = addToCart(user.token, productId);

        Resp rejected = checkout(user.token, UUID.randomUUID(), cartId, "LASTONE");

        assertEquals(409, rejected.status(), rejected.body());
        assertEquals("PROMOTION_USAGE_LIMIT_REACHED", rejected.errorCode());
        assertNoOrderOrRedemption();
        assertEquals(1, jdbc.queryForObject("select usage_count from promotion.promotions where promotion_id=?",
                Integer.class, promotionId));
    }

    @Test
    void perCustomerLimitIsRejected() throws Exception {
        UUID promotionId = seedPromotion("ONCEONLY", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 1, null, null, null);
        Session user = register();
        seedHistoricalReservation(promotionId, user.customerId);
        String cartId = addToCart(user.token, productId);

        Resp rejected = checkout(user.token, UUID.randomUUID(), cartId, "ONCEONLY");

        assertEquals(409, rejected.status(), rejected.body());
        assertEquals("PROMOTION_CUSTOMER_LIMIT_REACHED", rejected.errorCode());
        assertEquals(0, jdbc.queryForObject("select usage_count from promotion.promotions where promotion_id=?",
                Integer.class, promotionId));
    }

    @Test
    void idempotentReplayReturnsTheOriginalOrderWithoutSecondReservation() throws Exception {
        UUID promotionId = seedPromotion("REPLAY10", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, null, null, null);
        Session user = register();
        String cartId = addToCart(user.token, productId);
        UUID key = UUID.randomUUID();

        Resp first = checkout(user.token, key, cartId, "REPLAY10");
        Resp second = checkout(user.token, key, cartId, "REPLAY10");

        assertEquals(201, first.status(), first.body());
        assertEquals(201, second.status(), second.body());
        assertEquals(first.data().path("orderId").asText(), second.data().path("orderId").asText());
        assertEquals(1L, count("select count(*) from ordering.orders where cart_id=?", UUID.fromString(cartId)));
        assertEquals(1, jdbc.queryForObject("select usage_count from promotion.promotions where promotion_id=?",
                Integer.class, promotionId));
        assertEquals(1L, count("select count(*) from promotion.promotion_redemptions where promotion_id=?",
                promotionId));
    }

    @Test
    void sameKeyWithADifferentRequestIsAConflictAndMutatesNothing() throws Exception {
        UUID promotionId = seedPromotion("KEY10", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, null, null, null);
        Session user = register();
        String cartId = addToCart(user.token, productId);
        UUID key = UUID.randomUUID();
        assertEquals(201, checkout(user.token, key, cartId, "KEY10").status());

        Resp conflict = checkout(user.token, key, cartId, null);

        assertEquals(409, conflict.status(), conflict.body());
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.errorCode());
        assertEquals(1L, count("select count(*) from ordering.orders where cart_id=?", UUID.fromString(cartId)));
        assertEquals(1, jdbc.queryForObject("select usage_count from promotion.promotions where promotion_id=?",
                Integer.class, promotionId));
    }

    @Test
    void anotherCustomersCartCannotBeCheckedOut() throws Exception {
        seedPromotion("THEFT10", "PERCENTAGE", "10", "0", null, "USD", "ACTIVE",
                "now() - interval '1 hour'", "now() + interval '1 day'", 100, 5, null, null, null);
        Session owner = register();
        String cartId = addToCart(owner.token, productId);
        Session intruder = register();

        Resp rejected = checkout(intruder.token, UUID.randomUUID(), cartId, "THEFT10");

        assertEquals(404, rejected.status(), rejected.body());
        assertEquals("CART_NOT_FOUND", rejected.errorCode());
        assertEquals(0L, count("select count(*) from ordering.orders where cart_id=?", UUID.fromString(cartId)));
    }

    @Test
    void rejectionEnvelopeIsStableAndLeaksNoInternals() throws Exception {
        Session user = register();
        String cartId = addToCart(user.token, productId);

        Resp rejected = checkout(user.token, UUID.randomUUID(), cartId, "LEAKCHECK");

        assertEquals(409, rejected.status());
        String body = rejected.body().toLowerCase();
        assertFalse(body.contains("sql"), body);
        assertFalse(body.contains("constraint"), body);
        assertFalse(body.contains("exception"), body);
        assertFalse(body.contains("psql"), body);
        assertFalse(rejected.tree().path("requestId").asText().isBlank(), "correlation id must be present");
        assertEquals("PROMOTION_NOT_FOUND", rejected.errorCode());
    }

    // ------------------------------------------------------------- helpers

    private void assertRejected(String code, String expectedError) throws Exception {
        Session user = register();
        String cartId = addToCart(user.token, productId);

        Resp rejected = checkout(user.token, UUID.randomUUID(), cartId, code);

        assertEquals(409, rejected.status(), rejected.body());
        assertEquals(expectedError, rejected.errorCode(), rejected.body());
        assertNoOrderOrRedemption();
    }

    private void assertNoOrderOrRedemption() {
        assertEquals(0L, count("select count(*) from ordering.orders where store_id=?", storeId));
        assertEquals(0L, count("select count(*) from promotion.promotion_redemptions where store_id=?", storeId));
    }

    private String redemptionColumn(String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from promotion.promotion_redemptions "
                + "where store_id=?", String.class, storeId);
    }

    private BigDecimal redemptionDecimal(String column) {
        return jdbc.queryForObject("select " + column + " from promotion.promotion_redemptions where store_id=?",
                BigDecimal.class, storeId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private record Session(String token, UUID customerId) {
    }

    private record Resp(int status, String body) {
        JsonNode data() {
            return tree().path("data");
        }

        String errorCode() {
            return tree().path("error").path("code").asText();
        }

        JsonNode tree() {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Unparseable response: " + body, e);
            }
        }
    }

    private Resp checkout(String token, UUID key, String cartId, String code) {
        String json = "{\"cartId\":\"" + cartId + "\",\"fulfillmentType\":\"PICKUP\""
                + (code == null ? "" : ",\"promotionCode\":\"" + code + "\"") + "}";
        return checkoutRaw(token, key, json);
    }

    private Resp checkoutRaw(String token, UUID key, String json) {
        return send(HttpMethod.POST, ORDERS, json, token, key.toString());
    }

    private String addToCart(String token, UUID product) throws Exception {
        Resp added = send(HttpMethod.POST, CART_ITEMS, "{\"storeId\":\"" + storeId + "\",\"productId\":\"" + product
                + "\",\"quantity\":1,\"customizations\":[]}", token, null);
        assertEquals(200, added.status(), added.body());
        return added.data().path("cartId").asText();
    }

    private String addToCart(String token, UUID product, String cartId) throws Exception {
        Resp added = send(HttpMethod.POST, CART_ITEMS, "{\"storeId\":\"" + storeId + "\",\"productId\":\"" + product
                + "\",\"quantity\":1,\"customizations\":[]}", token, null);
        assertEquals(200, added.status(), added.body());
        assertEquals(cartId, added.data().path("cartId").asText());
        return cartId;
    }

    private Resp send(HttpMethod method, String path, String json, String bearer, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method.name(), json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null)
            builder.header("Authorization", "Bearer " + bearer);
        if (idempotencyKey != null)
            builder.header("Idempotency-Key", idempotencyKey);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("I/O failure while calling " + path, e);
        }
    }

    private Session register() throws Exception {
        String email = "checkout-promo-" + UUID.randomUUID() + "@smart.coffee";
        Resp response = send(HttpMethod.POST, "/api/v1/auth/register",
                mapper.writeValueAsString(new RegisterRequest("Promo Checkout Customer", email, "Str0ng!Pass", "en")),
                null, null);
        assertEquals(201, response.status(), "register: " + response.body());
        UUID customerId = jdbc.queryForObject("select c.customer_id from customer.customer_profiles c "
                + "join identity.users u on u.user_id = c.user_id where u.normalized_email = ?", UUID.class, email);
        return new Session(response.data().path("accessToken").asText(), customerId);
    }

    // ------------------------------------------------------------- raw seeding

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'B2 Checkout Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "B2" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedProduct(String code, String price) {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, true, true, now(), now(), 0)",
                id, code, category, "B2 " + code, dec(price));
        return id;
    }

    private void offer(UUID store, UUID product) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", store, product);
    }

    private UUID seedPromotion(String code, String type, String value, String minimum, String cap, String currency,
            String status, String startsExpression, String endsExpression, Integer usageLimit, Integer perCustomer,
            UUID storeLink, UUID productLink, UUID categoryLink) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into promotion.promotions (promotion_id, promotion_code, promotion_name, discount_type, "
                + "discount_value, minimum_order_amount, maximum_discount_amount, currency_code, starts_at, ends_at, "
                + "status, usage_limit, per_customer_limit, usage_count, stackable, automatic, priority, version) "
                + "values (?,?,?,?,?,?,?," + (currency == null ? "null" : "?") + "," + startsExpression + ","
                + endsExpression + ",?,?,?,0,false,false,0,0)",
                currency == null ? new Object[] { id, code, "Welcome", type, dec(value), dec(minimum),
                        cap == null ? null : dec(cap), status, usageLimit, perCustomer }
                        : new Object[] { id, code, "Welcome", type, dec(value), dec(minimum),
                                cap == null ? null : dec(cap), currency, status, usageLimit, perCustomer });
        if (storeLink != null)
            jdbc.update("insert into promotion.promotion_stores (promotion_id, store_id) values (?,?)", id, storeLink);
        if (productLink != null)
            jdbc.update("insert into promotion.promotion_products (promotion_id, product_id) values (?,?)", id,
                    productLink);
        if (categoryLink != null)
            jdbc.update("insert into promotion.promotion_categories (promotion_id, category_id) values (?,?)", id,
                    categoryLink);
        return id;
    }

    private void seedHistoricalReservation(UUID promotionId, UUID customerId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, customer_id, source, "
                + "fulfillment_type, status, payment_status, currency_code, subtotal, discount_amount, tax_amount, "
                + "delivery_fee, rounding_amount, total_amount, total_quantity, ordered_at, updated_at, version) "
                + "values (?,?,?,?,'CUSTOMER_WEB','PICKUP','AWAITING_PAYMENT','PENDING','USD',10,1,0.9,0,0,9.9,1,"
                + "now(),now(),0)", orderId, "HIST-" + suffix(), storeId, customerId);
        jdbc.update("insert into promotion.promotion_redemptions (redemption_id, promotion_id, customer_id, order_id, "
                + "store_id, status, discount_amount, currency_code, reserved_at, expires_at, version) "
                + "values (?,?,?,?,?,'RESERVED',1,'USD',now(),now() + interval '10 minutes',0)",
                UUID.randomUUID(), promotionId, customerId, orderId, storeId);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
