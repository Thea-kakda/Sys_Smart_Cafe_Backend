package com.smartcoffee.modules.promotion;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase B1 HTTP integration for the cart-owned promotion quote against a
 * disposable PostgreSQL container. Proves the customer contract over real HTTP:
 * identity comes from the authenticated actor, store/lines/currency from the
 * owned cart snapshots, client money and eligibility fields are ignored,
 * category and product targeting use OR, discount applies only to eligible
 * lines, and no redemption or usage_count mutation is ever performed. The legacy
 * management validate endpoint is gated by promotion:read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PromotionQuoteHttpIT extends IntegrationTestBase {

    private static final String QUOTE = "/api/v1/carts/current/promotion/quote";
    private static final String CART_ITEMS = "/api/v1/carts/current/items";
    private static final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    // --------------------------------------------------------- happy path

    @Test
    void cartOwnedQuoteUsesCategoryTargetingAndIgnoresClientSuppliedMoney() throws Exception {
        UUID store = seedStore();
        UUID catA = seedCategory();
        UUID catB = seedCategory();
        UUID prodA = seedProduct(catA, "10.00");
        UUID prodB = seedProduct(catB, "5.00");
        offer(store, prodA);
        offer(store, prodB);
        String code = uniqueCode("CAT");
        UUID promotion = seedPromotion(code, "PERCENTAGE", "10", "0", null, "USD", false, null, null);
        jdbc.update("insert into promotion.promotion_categories (promotion_id, category_id) values (?,?)", promotion,
                catA);

        Registration customer = register();
        String cartA = addItem(customer.token(), store, prodA);
        addItem(customer.token(), store, prodB);

        // Injected identity, money and eligibility must be ignored: the contract
        // accepts only storeId + promotionCode.
        Resp quote = send(HttpMethod.POST, QUOTE,
                "{\"storeId\":\"" + store + "\",\"promotionCode\":\"" + code + "\",\"customerId\":\""
                        + UUID.randomUUID() + "\",\"subtotal\":999,\"discountAmount\":50,\"eligible\":true,"
                        + "\"productIds\":[],\"categoryIds\":[],\"lines\":[]}",
                customer.token());
        assertEquals(200, quote.status(), quote.body());
        JsonNode data = quote.data();
        assertTrue(data.path("eligible").asBoolean());
        assertTrue(data.path("reasonCode").isNull());
        assertEquals(0, dec("10.0000").compareTo(new BigDecimal(data.path("eligibleSubtotal").asText())),
                "only the category-targeted line is eligible");
        assertEquals(0, dec("1.0000").compareTo(new BigDecimal(data.path("discountAmount").asText())),
                "client discount was ignored");
        assertEquals("USD", data.path("currencyCode").asText());
        assertEquals(1, data.path("affectedCartItemIds").size());
        assertEquals(cartA, data.path("affectedCartItemIds").get(0).asText());
        assertEquals(promotion.toString(), data.path("promotionId").asText());
        assertEquals(code, data.path("code").asText());
        assertEquals(0, data.path("promotionVersion").asLong());

        assertEquals(0, countRedemptions(promotion), "quote must not write a redemption");
        assertEquals(0, usageCount(promotion), "quote must not consume usage");
        assertEquals(2, cartLineCount(customer.customerId()), "quote must not alter the cart");
    }

    @Test
    void productOrCategoryTargetingCoversBothMatchingLines() throws Exception {
        UUID store = seedStore();
        UUID catA = seedCategory();
        UUID catB = seedCategory();
        UUID prodA = seedProduct(catA, "10.00");
        UUID prodB = seedProduct(catB, "5.00");
        offer(store, prodA);
        offer(store, prodB);
        String code = uniqueCode("OR");
        UUID promotion = seedPromotion(code, "PERCENTAGE", "10", "0", null, "USD", false, null, null);
        jdbc.update("insert into promotion.promotion_categories (promotion_id, category_id) values (?,?)", promotion,
                catA);
        jdbc.update("insert into promotion.promotion_products (promotion_id, product_id) values (?,?)", promotion,
                prodB);

        Registration customer = register();
        addItem(customer.token(), store, prodA);
        addItem(customer.token(), store, prodB);

        Resp quote = send(HttpMethod.POST, QUOTE, quoteBody(store, code), customer.token());
        assertEquals(200, quote.status(), quote.body());
        assertTrue(quote.data().path("eligible").asBoolean());
        assertEquals(0, dec("15.0000").compareTo(new BigDecimal(quote.data().path("eligibleSubtotal").asText())),
                "category line plus product line, OR semantics");
        assertEquals(0, dec("1.5000").compareTo(new BigDecimal(quote.data().path("discountAmount").asText())));
        assertEquals(2, quote.data().path("affectedCartItemIds").size());
    }

    @Test
    void minimumIsEvaluatedAgainstTheEligibleLineSubtotalOnly() throws Exception {
        UUID store = seedStore();
        UUID catA = seedCategory();
        UUID catB = seedCategory();
        UUID prodA = seedProduct(catA, "10.00");
        UUID prodB = seedProduct(catB, "40.00");
        offer(store, prodA);
        offer(store, prodB);
        String code = uniqueCode("MIN");
        UUID promotion = seedPromotion(code, "PERCENTAGE", "10", "20", null, "USD", false, null, null);
        jdbc.update("insert into promotion.promotion_products (promotion_id, product_id) values (?,?)", promotion,
                prodA);

        Registration customer = register();
        addItem(customer.token(), store, prodA);
        addItem(customer.token(), store, prodB);

        Resp quote = send(HttpMethod.POST, QUOTE, quoteBody(store, code), customer.token());
        assertEquals(200, quote.status(), quote.body());
        assertFalse(quote.data().path("eligible").asBoolean());
        assertEquals("PROMOTION_MINIMUM_NOT_MET", quote.data().path("reasonCode").asText());
        assertEquals(0, dec("10.0000").compareTo(new BigDecimal(quote.data().path("eligibleSubtotal").asText())),
                "the 40.00 ineligible line must not satisfy the minimum");
        assertEquals(0, countRedemptions(promotion), "rejection performs no redemption mutation");
        assertEquals(0, usageCount(promotion), "rejection does not increment usage_count");
    }

    @Test
    void mismatchedProductTargetIsNoEligibleItems() throws Exception {
        UUID store = seedStore();
        UUID category = seedCategory();
        UUID product = seedProduct(category, "10.00");
        offer(store, product);
        String code = uniqueCode("MIS");
        UUID promotion = seedPromotion(code, "PERCENTAGE", "10", "0", null, "USD", false, null, null);
        UUID otherProduct = seedProduct(seedCategory(), "10.00");
        jdbc.update("insert into promotion.promotion_products (promotion_id, product_id) values (?,?)", promotion,
                otherProduct);

        Registration customer = register();
        addItem(customer.token(), store, product);

        Resp quote = send(HttpMethod.POST, QUOTE, quoteBody(store, code), customer.token());
        assertEquals(200, quote.status(), quote.body());
        assertEquals("PROMOTION_NO_ELIGIBLE_ITEMS", quote.data().path("reasonCode").asText());
        assertEquals(0, countRedemptions(promotion));
    }

    // ------------------------------------------------------------ isolation

    @Test
    void customerWithoutAnOwnedCartCannotQuote() throws Exception {
        UUID store = seedStore();
        UUID category = seedCategory();
        UUID product = seedProduct(category, "10.00");
        offer(store, product);
        String code = uniqueCode("ISO");
        seedPromotion(code, "PERCENTAGE", "10", "0", null, "USD", false, null, null);

        Registration stranger = register();
        Resp quote = send(HttpMethod.POST, QUOTE, quoteBody(store, code), stranger.token());

        assertEquals(404, quote.status(), quote.body());
        assertEquals("CART_NOT_FOUND", quote.errorCode());
    }

    @Test
    void crossStoreContextIsRejected() throws Exception {
        UUID storeA = seedStore();
        UUID storeB = seedStore();
        UUID category = seedCategory();
        UUID product = seedProduct(category, "10.00");
        offer(storeA, product);
        String code = uniqueCode("XST");
        seedPromotion(code, "PERCENTAGE", "10", "0", null, "USD", false, null, null);

        Registration customer = register();
        addItem(customer.token(), storeA, product);

        Resp quote = send(HttpMethod.POST, QUOTE, quoteBody(storeB, code), customer.token());
        assertEquals(404, quote.status(), quote.body());
        assertEquals("CART_NOT_FOUND", quote.errorCode(), "no cart is resolved across stores");
    }

    @Test
    void unauthenticatedQuoteIsRejected() throws Exception {
        UUID store = seedStore();
        Resp quote = send(HttpMethod.POST, QUOTE,
                "{\"storeId\":\"" + store + "\",\"promotionCode\":\"WELCOME10\"}", null);

        assertTrue(quote.status() == 401 || quote.status() == 403, "expected auth rejection, got " + quote.status());
    }

    // --------------------------------------------------- temporal and limits

    @Test
    void expiredPromotionReturnsAStableReason() throws Exception {
        UUID store = seedStore();
        UUID category = seedCategory();
        UUID product = seedProduct(category, "10.00");
        offer(store, product);
        String code = uniqueCode("EXP");
        seedPromotion(code, "PERCENTAGE", "10", "0", null, "USD", true, null, null);

        Registration customer = register();
        addItem(customer.token(), store, product);

        Resp quote = send(HttpMethod.POST, QUOTE, quoteBody(store, code), customer.token());
        assertEquals(200, quote.status(), quote.body());
        assertFalse(quote.data().path("eligible").asBoolean());
        assertEquals("PROMOTION_EXPIRED", quote.data().path("reasonCode").asText());
    }

    @Test
    void globalUsageLimitReachedReturnsAStableReason() throws Exception {
        UUID store = seedStore();
        UUID category = seedCategory();
        UUID product = seedProduct(category, "10.00");
        offer(store, product);
        String code = uniqueCode("GLB");
        UUID promotion = seedPromotion(code, "PERCENTAGE", "10", "0", null, "USD", false, 1, null);
        jdbc.update("update promotion.promotions set usage_count=1 where promotion_id=?", promotion);

        Registration customer = register();
        addItem(customer.token(), store, product);

        Resp quote = send(HttpMethod.POST, QUOTE, quoteBody(store, code), customer.token());
        assertEquals(200, quote.status(), quote.body());
        assertEquals("PROMOTION_USAGE_LIMIT_REACHED", quote.data().path("reasonCode").asText());
        assertEquals(1, usageCount(promotion), "read-side check never changes usage_count");
    }

    @Test
    void perCustomerLimitCountsReservedButIgnoresReleasedRedemptions() throws Exception {
        UUID store = seedStore();
        UUID category = seedCategory();
        UUID product = seedProduct(category, "10.00");
        offer(store, product);
        String code = uniqueCode("CUS");
        UUID promotion = seedPromotion(code, "PERCENTAGE", "10", "0", null, "USD", false, 10, 1);

        Registration customer = register();
        addItem(customer.token(), store, product);

        UUID order = seedOrder(store);
        UUID redemption = UUID.randomUUID();
        jdbc.update("insert into promotion.promotion_redemptions (redemption_id, promotion_id, customer_id, "
                + "order_id, store_id, status, discount_amount, currency_code, reserved_at) values "
                + "(?,?,?,?,?,'RESERVED',1,'USD',now())", redemption, promotion, customer.customerId(), order, store);

        Resp blocked = send(HttpMethod.POST, QUOTE, quoteBody(store, code), customer.token());
        assertEquals(200, blocked.status(), blocked.body());
        assertEquals("PROMOTION_CUSTOMER_LIMIT_REACHED", blocked.data().path("reasonCode").asText());

        jdbc.update("update promotion.promotion_redemptions set status='RELEASED', released_at=now() "
                + "where redemption_id=?", redemption);
        Resp allowed = send(HttpMethod.POST, QUOTE, quoteBody(store, code), customer.token());
        assertEquals(200, allowed.status(), allowed.body());
        assertTrue(allowed.data().path("eligible").asBoolean(), "released reservations do not count");
    }

    // ------------------------------------------------------- legacy endpoint

    @Test
    void legacyValidateEndpointNowRequiresPromotionReadPermission() throws Exception {
        UUID store = seedStore();
        Registration customer = register();
        Resp legacy = send(HttpMethod.POST, "/api/v1/promotions/validate",
                "{\"storeId\":\"" + store + "\",\"customerId\":\"" + customer.customerId() + "\",\"orderId\":\""
                        + UUID.randomUUID() + "\",\"promotionCode\":\"WELCOME10\",\"subtotal\":20,"
                        + "\"currencyCode\":\"USD\"}",
                customer.token());

        assertEquals(403, legacy.status(), "client-trusting endpoint is management diagnostics only: " + legacy.body());
    }

    // ------------------------------------------------------------- seeding

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'B1 Quote Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "B1" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedCategory() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name) "
                + "values (?,?,?)", id, "B1CAT" + UUID.randomUUID().toString().substring(0, 8), "B1 Category");
        return id;
    }

    private UUID seedProduct(UUID categoryId, String base) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, true, true, now(), now(), 0)", id,
                "B1PROD" + UUID.randomUUID().toString().substring(0, 8), categoryId, "B1 Product", dec(base));
        return id;
    }

    private void offer(UUID storeId, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", storeId, productId);
    }

    private UUID seedPromotion(String code, String type, String value, String minimum, String maximum,
            String currency, boolean expired, Integer usageLimit, Integer perCustomerLimit) {
        UUID id = UUID.randomUUID();
        String start = expired ? "now() - interval '2 days'" : "now() - interval '1 hour'";
        String end = expired ? "now() - interval '1 day'" : "now() + interval '1 day'";
        jdbc.update("insert into promotion.promotions (promotion_id, promotion_code, promotion_name, discount_type, "
                + "discount_value, minimum_order_amount, maximum_discount_amount, currency_code, starts_at, ends_at, "
                + "status, usage_limit, per_customer_limit, usage_count, stackable, automatic, priority, version) "
                + "values (?,?, 'B1 Campaign', ?, ?, ?, ?, ?, " + start + ", " + end
                + ", 'ACTIVE', ?, ?, 0, false, false, 0, 0)", id, code, type, dec(value), dec(minimum),
                maximum == null ? null : dec(maximum), currency, usageLimit, perCustomerLimit);
        return id;
    }

    private UUID seedOrder(UUID storeId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, source, fulfillment_type, status, "
                + "payment_status, currency_code, subtotal, total_amount, total_quantity, ordered_at, updated_at) "
                + "values (?,? ,?,'POS','DINE_IN','CONFIRMED','PAID','USD',20,18,2,now(),now())", id,
                "B1-" + UUID.randomUUID().toString().substring(0, 8), storeId);
        return id;
    }

    // ------------------------------------------------------------- helpers

    private record Registration(String token, UUID customerId) {
    }

    private Registration register() throws Exception {
        String email = "b1-quote-" + UUID.randomUUID() + "@smart.coffee";
        Resp response = send(HttpMethod.POST, "/api/v1/auth/register",
                mapper.writeValueAsString(new RegisterRequest("B1 Quote Customer", email, "Str0ng!Pass", "en")), null);
        assertEquals(201, response.status(), "register: " + response.body());
        UUID userId = jdbc.queryForObject("select user_id from identity.users where email=?", UUID.class, email);
        UUID customerId = jdbc.queryForObject(
                "select customer_id from customer.customer_profiles where user_id=?", UUID.class, userId);
        return new Registration(response.data().path("accessToken").asText(), customerId);
    }

    private String addItem(String token, UUID storeId, UUID productId) throws Exception {
        Resp added = send(HttpMethod.POST, CART_ITEMS, "{\"storeId\":\"" + storeId + "\",\"productId\":\""
                + productId + "\",\"quantity\":1,\"customizations\":[]}", token);
        assertEquals(200, added.status(), added.body());
        return added.data().path("items").get(0).path("cartItemId").asText();
    }

    private String quoteBody(UUID storeId, String code) {
        return "{\"storeId\":\"" + storeId + "\",\"promotionCode\":\"" + code + "\"}";
    }

    private static String uniqueCode(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace("-", "");
    }

    private int countRedemptions(UUID promotion) {
        Integer count = jdbc.queryForObject(
                "select count(*) from promotion.promotion_redemptions where promotion_id=?", Integer.class, promotion);
        return count == null ? 0 : count;
    }

    private int usageCount(UUID promotion) {
        Integer count = jdbc.queryForObject(
                "select usage_count from promotion.promotions where promotion_id=?", Integer.class, promotion);
        return count == null ? 0 : count;
    }

    private int cartLineCount(UUID customerId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from cart.cart_items i join cart.carts c on c.cart_id=i.cart_id "
                        + "where c.customer_id=?",
                Integer.class, customerId);
        return count == null ? 0 : count;
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    // ---------------------------------------------------------- HTTP plumbing

    private record Resp(int status, String body) {
        JsonNode data() {
            return tree().path("data");
        }

        String errorCode() {
            return tree().path("error").path("code").asText();
        }

        private JsonNode tree() {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Unparseable response: " + body, e);
            }
        }
    }

    private Resp send(HttpMethod method, String path, String json, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method.name(),
                        json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null)
            builder.header("Authorization", "Bearer " + bearer);
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
}
