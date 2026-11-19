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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * HTTP contract for the order-detail deduplication correction: the customer
 * detail and history endpoints must return one entry per business line with all
 * its customizations, stable across reloads, and must not leak another
 * customer's order or persistence internals.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderDetailHttpIT extends IntegrationTestBase {

    private static final String ORDERS = "/api/v1/orders";
    private static final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    // --------------------------------------------------------------- tests

    @Test
    void customerDetailAndHistoryReturnSingleLineWithEveryCustomization() throws Exception {
        UUID store = seedStore();
        Session user = register();
        UUID orderId = insertOrder("H-1", store, user.customerId);
        UUID itemId = insertItem(orderId, 1);
        insertCustomization(itemId, 1, "Milk", "Fresh");
        insertCustomization(itemId, 2, "Sugar", "50%");
        insertCustomization(itemId, 3, "Extras", "Shot");

        Resp detail = send(HttpMethod.GET, ORDERS + "/" + orderId, null, user.token);

        assertEquals(200, detail.status(), detail.body());
        JsonNode data = detail.data();
        JsonNode items = data.path("items");
        assertEquals(1, items.size(), "business line must appear exactly once");
        assertEquals(itemId.toString(), items.get(0).path("orderItemId").asText());
        assertEquals(3, items.get(0).path("customizations").size(), "every customization exactly once");
        assertEquals("Milk: Fresh", items.get(0).path("customizations").get(0).asText());
        assertEquals("Sugar: 50%", items.get(0).path("customizations").get(1).asText());
        assertEquals("Extras: Shot", items.get(0).path("customizations").get(2).asText());
        assertEquals(0, new BigDecimal("7.00").compareTo(new BigDecimal(data.path("totalAmount").asText())));
        assertEquals(1, data.path("totalQuantity").asInt());

        Resp reload = send(HttpMethod.GET, ORDERS + "/" + orderId, null, user.token);
        assertEquals(200, reload.status(), reload.body());
        assertEquals(items, reload.data().path("items"), "reload must return the identical line shape");

        Resp history = send(HttpMethod.GET, ORDERS + "?page=0&size=20", null, user.token);
        assertEquals(200, history.status(), history.body());
        JsonNode historical = null;
        for (JsonNode row : history.data().path("items")) {
            if (orderId.toString().equals(row.path("orderId").asText())) {
                historical = row;
            }
        }
        assertNotNull(historical, "history must contain the order");
        assertEquals(1, historical.path("items").size());
        assertEquals(3, historical.path("items").get(0).path("customizations").size());
    }

    @Test
    void otherCustomerCannotReadTheOrderAndGetsNoInternals() throws Exception {
        UUID store = seedStore();
        Session owner = register();
        UUID orderId = insertOrder("H-2", store, owner.customerId);
        UUID itemId = insertItem(orderId, 1);
        insertCustomization(itemId, 1, "Milk", "Fresh");
        Session stranger = register();

        Resp denied = send(HttpMethod.GET, ORDERS + "/" + orderId, null, stranger.token);

        assertEquals(404, denied.status(), "another customer's order is indistinguishable from a missing one");
        String body = denied.body().toLowerCase();
        assertFalse(body.contains("sql"), body);
        assertFalse(body.contains("constraint"), body);
        assertFalse(body.contains("exception"), body);
        assertFalse(body.contains("psql"), body);
        assertFalse(body.contains("stack"), body);
    }

    @Test
    void promotionAndVariantSnapshotsAreStableAcrossReads() throws Exception {
        UUID store = seedStore();
        Session user = register();
        UUID promotionId = seedPromotion();
        UUID orderId = insertPromotedOrder("H-3", store, user.customerId, promotionId);
        UUID itemId = insertVariantedItem(orderId, 1);
        insertCustomization(itemId, 1, "Milk", "Fresh");

        Resp detail = send(HttpMethod.GET, ORDERS + "/" + orderId, null, user.token);

        assertEquals(200, detail.status(), detail.body());
        JsonNode data = detail.data();
        assertEquals(promotionId.toString(), data.path("promotionId").asText());
        assertEquals("SAVE10", data.path("promotionCode").asText());
        assertEquals("Welcome", data.path("promotionName").asText());
        assertEquals(0, new BigDecimal("1.00").compareTo(new BigDecimal(data.path("promotionDiscountAmount").asText())));
        assertEquals(0, new BigDecimal("6.00").compareTo(new BigDecimal(data.path("totalAmount").asText())));
        assertEquals(1, data.path("items").size());
        assertEquals("LARGE", jdbc.queryForObject(
                "select variant_code_snapshot from ordering.order_items where order_item_id = ?::uuid",
                String.class, itemId.toString()));
        assertEquals(0, new BigDecimal("4.50").compareTo(jdbc.queryForObject(
                "select variant_price_snapshot from ordering.order_items where order_item_id = ?::uuid",
                BigDecimal.class, itemId.toString())));
    }

    // ------------------------------------------------------------- helpers

    private record Session(String token, UUID customerId) {
    }

    private record Resp(int status, String body) {
        JsonNode data() {
            return tree().path("data");
        }

        JsonNode tree() {
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
                .method(method.name(), json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
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

    private Session register() throws Exception {
        String email = "dedup-http-" + UUID.randomUUID() + "@smart.coffee";
        Resp response = send(HttpMethod.POST, "/api/v1/auth/register",
                mapper.writeValueAsString(new RegisterRequest("Dedup HTTP Customer", email, "Str0ng!Pass", "en")),
                null);
        assertEquals(201, response.status(), "register: " + response.body());
        UUID customerId = jdbc.queryForObject("select c.customer_id from customer.customer_profiles c "
                + "join identity.users u on u.user_id = c.user_id where u.normalized_email = ?", UUID.class, email);
        return new Session(response.data().path("accessToken").asText(), customerId);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?::uuid, ?, 'Dedup HTTP Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, "
                + "now(), now(), 0)", id.toString(), "DHP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedPromotion() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into promotion.promotions (promotion_id, promotion_code, promotion_name, discount_type, "
                + "discount_value, minimum_order_amount, maximum_discount_amount, currency_code, starts_at, ends_at, "
                + "status, usage_limit, per_customer_limit, usage_count, stackable, automatic, priority, version) "
                + "values (?,?, 'Welcome', 'PERCENTAGE', 10.00, 0, null, 'USD', now() - interval '1 hour', "
                + "now() + interval '1 day', 'ACTIVE', 100, 5, 0, false, false, 0, 0)",
                id, "SAVE" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        return id;
    }

    private UUID insertOrder(String number, UUID storeId, UUID customerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, customer_id, source, "
                + "fulfillment_type, status, payment_status, currency_code, subtotal, total_amount, total_quantity, "
                + "ordered_at, updated_at) values (?::uuid, ?, ?::uuid, ?::uuid, 'CUSTOMER_WEB', 'PICKUP', "
                + "'CONFIRMED', 'PAID', 'USD', 7.00, 7.00, 1, now(), now())",
                id.toString(), number, storeId.toString(), customerId.toString());
        return id;
    }

    private UUID insertPromotedOrder(String number, UUID storeId, UUID customerId, UUID promotionId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, customer_id, source, "
                + "fulfillment_type, status, payment_status, currency_code, subtotal, discount_amount, total_amount, "
                + "total_quantity, ordered_at, updated_at, promotion_id, promotion_code_snapshot, "
                + "promotion_name_snapshot, promotion_type_snapshot, promotion_value_snapshot, "
                + "promotion_eligible_subtotal, promotion_discount_amount, promotion_version) "
                + "values (?::uuid, ?, ?::uuid, ?::uuid, 'CUSTOMER_WEB', 'PICKUP', 'CONFIRMED', 'PAID', 'USD', "
                + "7.00, 1.00, 6.00, 1, now(), now(), ?::uuid, 'SAVE10', 'Welcome', 'PERCENTAGE', 10.00, 7.00, "
                + "1.00, 3)",
                id.toString(), number, storeId.toString(), customerId.toString(), promotionId.toString());
        return id;
    }

    private UUID insertItem(UUID orderId, int index) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.order_items (order_item_id, order_id, product_code_snapshot, "
                + "product_name_snapshot, quantity, unit_base_price, unit_customization_price, line_discount_amount, "
                + "line_tax_amount, line_total, created_at) values (?::uuid, ?::uuid, ?, ?, 1, 3.50, 0.00, "
                + "0.00, 0.00, 3.50, ?)",
                id.toString(), orderId.toString(), "P-" + index, "Latte " + index,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(index)));
        return id;
    }

    private UUID insertVariantedItem(UUID orderId, int index) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.order_items (order_item_id, order_id, variant_code_snapshot, "
                + "variant_name_snapshot, variant_price_snapshot, product_code_snapshot, product_name_snapshot, "
                + "quantity, unit_base_price, unit_customization_price, line_discount_amount, line_tax_amount, "
                + "line_total, created_at) values (?::uuid, ?::uuid, 'LARGE', 'Large', 4.50, ?, ?, 1, "
                + "4.50, 0.00, 0.00, 0.00, 4.50, ?)",
                id.toString(), orderId.toString(),
                "P-" + index, "Latte " + index,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(index)));
        return id;
    }

    private UUID insertCustomization(UUID itemId, int index, String group, String option) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.order_item_customizations (order_item_customization_id, order_item_id, "
                + "group_name_snapshot, option_name_snapshot, additional_price, quantity, created_at) "
                + "values (?::uuid, ?::uuid, ?, ?, 0.00, 1, ?)",
                id.toString(), itemId.toString(), group, option,
                java.sql.Timestamp.from(java.time.Instant.now().plusMillis(index)));
        return id;
    }
}
