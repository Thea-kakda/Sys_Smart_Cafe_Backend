package com.smartcoffee.modules.ordering;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.auth.api.RegisterRequest;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.CreateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.api.request.RecipeItemRequest;
import com.smartcoffee.modules.recipe.application.RecipeAssignmentService;
import com.smartcoffee.modules.recipe.application.RecipeCommandService;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Slice 2D §23 — the conversion and the settlement on the real HTTP stack
 * (RANDOM_PORT, real JWT filter chain, Flyway V001..V046).
 *
 * <p>Two things are asserted end to end, exactly as a client would see them:
 *
 * <ol>
 *   <li>{@code POST /api/v1/orders} converts the cart hold: the response is the
 *       usual {@code 201 ApiResponse<OrderResponse>}, the cart row becomes
 *       {@code CONVERTED}, the order owns one reservation with the cart's
 *       snapshot, and no release is written. Replaying the same
 *       {@code Idempotency-Key} replays the order; a new key is rejected with
 *       {@code 409 CART_ALREADY_CHECKED_OUT}.</li>
 *   <li>{@code POST /api/v1/payments/cash} consumes the converted snapshot once:
 *       stock falls at the HTTP boundary, a second settlement is
 *       {@code 409 ORDER_ALREADY_PAID}, and the error envelope keeps the standard
 *       {@code error.code} shape.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutConversionHttpIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final String CART_ITEMS = "/api/v1/carts/current/items";
    private static final String ORDERS = "/api/v1/orders";
    private static final String CASH = "/api/v1/payments/cash";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired RecipeCommandService recipeCommands;
    @Autowired RecipeAssignmentService assignment;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private CurrentActor admin;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        admin = seedAdmin();
        storeId = seedStore();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void checkoutConvertsAndSettlementConsumesOverHttp() throws Exception {
        Latte l = latte();
        Session session = register();

        Resp added = send(HttpMethod.POST, CART_ITEMS, addBody(l.productId), session.token(), null);
        assertEquals(200, added.status(), added.body());
        String cartId = added.data().path("cartId").asText();
        assertEquals(0, dec("18").compareTo(reserved(l.beans)));

        String key = UUID.randomUUID().toString();
        Resp placed = send(HttpMethod.POST, ORDERS, orderBody(cartId), session.token(), key);
        assertEquals(201, placed.status(), placed.body());
        String orderId = placed.data().path("orderId").asText();
        assertEquals("AWAITING_PAYMENT", placed.data().path("status").asText());
        assertEquals("PENDING", placed.data().path("paymentStatus").asText());

        // The conversion is visible in the database, not in the response body.
        String cartItemId = added.data().path("items").get(0).path("cartItemId").asText();
        assertEquals("CONVERTED", reservationColumn(cartItemId, "status"));
        assertEquals(orderId, reservationColumn(cartItemId, "converted_order_id"));
        assertEquals("ACTIVE", orderReservationColumn(orderId, "status"));
        assertEquals("ORDER", orderReservationColumn(orderId, "source_type"));
        assertEquals(0, dec("18").compareTo(reserved(l.beans)), "the hold is transferred, never re-released");
        assertEquals(0L, releaseHeaders(), "conversion writes no SALE_RELEASE");
        assertEquals(0L, consumptionHeaders());
        assertEquals("CHECKED_OUT", jdbc.queryForObject("select status from cart.carts where cart_id=?",
                String.class, UUID.fromString(cartId)));

        // Replaying the checkout key replays the order; a fresh key is a conflict.
        Resp replayed = send(HttpMethod.POST, ORDERS, orderBody(cartId), session.token(), key);
        assertEquals(orderId, replayed.data().path("orderId").asText(), "the same key replays the same order");
        Resp conflict = send(HttpMethod.POST, ORDERS, orderBody(cartId), session.token(), UUID.randomUUID().toString());
        assertEquals(409, conflict.status(), conflict.body());
        assertEquals("CART_ALREADY_CHECKED_OUT", conflict.errorCode());

        // Settlement draws the snapshot down exactly once.
        String cashBody = "{\"orderId\":\"" + orderId + "\",\"storeId\":\"" + storeId
                + "\",\"amountReceived\":20,\"tenderCurrency\":\"USD\"}";
        Resp paid = send(HttpMethod.POST, CASH, cashBody, session.token(), UUID.randomUUID().toString());
        assertEquals(200, paid.status(), paid.body());
        assertEquals("PAID", paid.data().path("status").asText());

        assertEquals(1L, consumptionHeaders(), "settlement consumes exactly once");
        assertEquals(0, dec("982").compareTo(onHand(l.beans)));
        assertEquals(0, dec("0").compareTo(reserved(l.beans)));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "select status from ordering.orders where order_id=?", String.class, UUID.fromString(orderId)));

        Resp again = send(HttpMethod.POST, CASH, cashBody, session.token(), UUID.randomUUID().toString());
        assertEquals(409, again.status(), again.body());
        assertEquals("ORDER_ALREADY_PAID", again.errorCode());
        assertEquals(1L, consumptionHeaders(), "no second consumption may be written");
        assertEquals(0, dec("982").compareTo(onHand(l.beans)));
    }

    @Test
    void anInsufficientTenderKeepsTheHoldAndConsumesNothing() throws Exception {
        Latte l = latte();
        Session session = register();
        Resp added = send(HttpMethod.POST, CART_ITEMS, addBody(l.productId), session.token(), null);
        String cartId = added.data().path("cartId").asText();
        Resp placed = send(HttpMethod.POST, ORDERS, orderBody(cartId), session.token(), UUID.randomUUID().toString());
        assertEquals(201, placed.status(), placed.body());
        String orderId = placed.data().path("orderId").asText();

        String cashBody = "{\"orderId\":\"" + orderId + "\",\"storeId\":\"" + storeId
                + "\",\"amountReceived\":1,\"tenderCurrency\":\"USD\"}";
        Resp rejected = send(HttpMethod.POST, CASH, cashBody, session.token(), UUID.randomUUID().toString());

        assertEquals(409, rejected.status(), rejected.body());
        assertEquals("INSUFFICIENT_CASH_RECEIVED", rejected.errorCode());
        assertEquals("AWAITING_PAYMENT", jdbc.queryForObject(
                "select status from ordering.orders where order_id=?", String.class, UUID.fromString(orderId)));
        assertEquals("ACTIVE", orderReservationColumn(orderId, "status"), "the hold stays available for a retry");
        assertEquals(0, dec("18").compareTo(reserved(l.beans)));
        assertEquals(0, dec("1000").compareTo(onHand(l.beans)));
        assertEquals(0L, consumptionHeaders());
    }

    // ------------------------------------------------------------- HTTP client

    private record Session(String token) {
    }

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

    private Resp send(HttpMethod method, String path, String json, String bearer, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method.name(), json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
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
        String email = "checkout-http-" + UUID.randomUUID() + "@smart.coffee";
        Resp response = send(HttpMethod.POST, "/api/v1/auth/register",
                mapper.writeValueAsString(new RegisterRequest("Checkout Http Customer", email, "Str0ng!Pass", "en")),
                null, null);
        assertEquals(201, response.status(), "register: " + response.body());
        return new Session(response.data().path("accessToken").asText());
    }

    private String addBody(UUID productId) {
        return "{\"storeId\":\"" + storeId + "\",\"productId\":\"" + productId
                + "\",\"quantity\":1,\"customizations\":[]}";
    }

    private String orderBody(String cartId) {
        return "{\"cartId\":\"" + cartId + "\",\"fulfillmentType\":\"PICKUP\"}";
    }

    // ------------------------------------------------------------- assertions

    private BigDecimal reserved(UUID ingredient) {
        return jdbc.queryForObject("select reserved_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private BigDecimal onHand(UUID ingredient) {
        return jdbc.queryForObject("select current_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private long consumptionHeaders() {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_CONSUMPTION'", Long.class, storeId);
    }

    private long releaseHeaders() {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_RELEASE'", Long.class, storeId);
    }

    private String reservationColumn(String cartItemId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from inventory.inventory_reservations "
                + "where cart_item_id=?", String.class, UUID.fromString(cartItemId));
    }

    private String orderReservationColumn(String orderId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from inventory.inventory_reservations "
                + "where order_id=?", String.class, UUID.fromString(orderId));
    }

    // --------------------------------------------------------------- fixtures

    private record Latte(UUID productId, UUID beans) {
    }

    private Latte latte() {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID productId = UUID.randomUUID();
        String code = "HTTP_CHECKOUT_" + suffix();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, true, true, now(), now(), 0)",
                productId, code, category, "IT " + code, dec("10"));
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", storeId, productId);

        UUID beans = seedIngredient("HTTP_CHECKOUT_BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("HTTP_CHECKOUT_MILK_" + suffix(), GRAM);
        seedBalance(beans);
        seedBalance(milk);

        List<RecipeItemRequest> lines = List.of(
                new RecipeItemRequest(beans, dec("18"), BigDecimal.ZERO, null, RecipeScalingRule.FIXED, 0, false, null),
                new RecipeItemRequest(milk, dec("180"), BigDecimal.ZERO, null, RecipeScalingRule.FIXED, 1, false, null));
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, code, lines));
        RecipeVersionResponse active = recipeCommands.activate(admin, draft.recipeVersionId(), draft.version());
        assignment.assignProduct(admin, productId, new AssignProductRecipeRequest(active.recipeVersionId(),
                jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class, productId)));
        return new Latte(productId, beans);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Checkout Http Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "COH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private CurrentActor seedAdmin() {
        UUID id = UUID.randomUUID();
        String email = "checkout-http-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Checkout Http Admin", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), id, UUID.fromString(SUPER_ADMIN), null,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        return actors.require();
    }

    private UUID seedIngredient(String code, UUID baseUnit) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, "
                + "base_unit_id, standard_cost, track_inventory, track_batches, active, created_at, updated_at, "
                + "version) values (?,?,?,?,0,true,false,true,now(),now(),0)", id, code, code, baseUnit);
        return id;
    }

    private void seedBalance(UUID ingredient) {
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id, current_stock, "
                + "reserved_stock, minimum_stock, average_unit_cost) values (?,?,1000,0,0,0.025)", storeId, ingredient);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
