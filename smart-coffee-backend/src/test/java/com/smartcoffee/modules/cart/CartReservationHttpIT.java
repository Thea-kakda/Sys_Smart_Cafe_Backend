package com.smartcoffee.modules.cart;

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
 * Slice 2C §20 — the reservation behaviour of the cart API on the real HTTP
 * stack (RANDOM_PORT, real JWT filter chain, Flyway V001..V045).
 *
 * <p>Follows the project's plain-{@code RestTemplate} convention
 * ({@code RecipeAssignmentHttpIT}): the customer registers over HTTP (which
 * provisions the customer profile the cart needs) and every cart call carries
 * the bearer token. The catalog/recipe fixtures are seeded through the
 * application services on the test thread, so no management HTTP surface is
 * needed here.
 *
 * <p>Response compatibility is asserted directly: the cart body keeps its
 * pre-2C shape ({@code cartId}/{@code items[]}/{@code totalAmount}), the errors
 * keep the standard {@code error.code} envelope, and no reservation internals
 * leak into the customer response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CartReservationHttpIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final String ITEMS = "/api/v1/carts/current/items";

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

    // ---------------------------------------------------------------- add + retry

    @Test
    void addOverHttpReservesAndRetriesReplayTheOriginalCart() throws Exception {
        Latte l = latte();
        Session session = register();

        String key = UUID.randomUUID().toString();
        Resp first = add(session, key, body(l.productId, 1));
        assertEquals(200, first.status(), first.body());
        String cartId = first.data().path("cartId").asText();
        String itemId = first.data().path("items").get(0).path("cartItemId").asText();
        assertEquals(1, first.data().path("items").size());
        assertEquals("ACTIVE", first.data().path("status").asText());
        assertTrue(first.data().path("totalAmount").asDouble() > 0, "cart totals are still returned");
        assertEquals(0, dec("18").compareTo(reserved(l.beans)));

        // Same key + same body: replayed, not reserved twice.
        Resp retry = add(session, key, body(l.productId, 1));
        assertEquals(200, retry.status(), retry.body());
        assertEquals(cartId, retry.data().path("cartId").asText());
        assertEquals(1, retry.data().path("items").size());
        assertEquals(0, dec("18").compareTo(reserved(l.beans)), "retry must not double reserve");
        assertEquals(1L, ledgerHeaders(cartId, "SALE_RESERVATION"));

        // Same key + different body: conflict, no state change.
        Resp reused = add(session, key, body(l.productId, 3));
        assertEquals(409, reused.status(), reused.body());
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.errorCode());
        assertEquals(0, dec("18").compareTo(reserved(l.beans)));

        // Quantity update moves only the delta, remove releases everything.
        Resp up = send(HttpMethod.PATCH, "/api/v1/carts/" + cartId + "/items/" + itemId,
                "{\"quantity\":3}", session.token(), null);
        assertEquals(200, up.status(), up.body());
        assertEquals(3, up.data().path("items").get(0).path("quantity").asInt());
        assertEquals(0, dec("54").compareTo(reserved(l.beans)));

        Resp removed = send(HttpMethod.DELETE, "/api/v1/carts/" + cartId + "/items/" + itemId, null,
                session.token(), null);
        assertEquals(200, removed.status(), removed.body());
        assertEquals(0, removed.data().path("items").size());
        assertEquals(0, dec("0").compareTo(reserved(l.beans)));
        assertEquals(1L, ledgerHeaders(cartId, "SALE_RELEASE"));
        assertNoConsumption();
    }

    @Test
    void clearOverHttpReleasesEverything() throws Exception {
        Latte l = latte();
        Session session = register();
        Resp add = add(session, null, body(l.productId, 2));
        assertEquals(200, add.status(), add.body());
        String cartId = add.data().path("cartId").asText();
        assertEquals(0, dec("36").compareTo(reserved(l.beans)));

        Resp cleared = send(HttpMethod.DELETE, "/api/v1/carts/" + cartId + "/items", null, session.token(), null);
        assertEquals(200, cleared.status(), cleared.body());
        assertEquals(0, cleared.data().path("items").size());
        assertEquals(0, dec("0").compareTo(reserved(l.beans)));
        assertNoConsumption();
    }

    // -------------------------------------------------------------- error paths

    @Test
    void insufficientStockIsAStableBusinessErrorOverHttp() throws Exception {
        Latte l = latte();
        jdbc.update("update inventory.inventory_balances set current_stock=10 where store_id=? and ingredient_id=?",
                storeId, l.milk);
        Session session = register();

        Resp failed = add(session, null, body(l.productId, 1));
        assertEquals(409, failed.status(), failed.body());
        assertEquals("INSUFFICIENT_STOCK", failed.errorCode());
        assertTrue(failed.errorMessage().contains("available"), failed.errorMessage());
        // Nothing partial: no hold, no line, no ledger row.
        assertEquals(0, dec("0").compareTo(reserved(l.beans)));
        assertEquals(0L, jdbc.queryForObject("select count(*) from cart.cart_items i join cart.carts c "
                + "on c.cart_id=i.cart_id where c.store_id=?", Long.class, storeId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_RESERVATION'", Long.class, storeId));
    }

    @Test
    void missingRequiredCustomizationIsRejectedBeforeReserving() throws Exception {
        Latte l = latte();
        UUID groupId = seedGroup("MILK", "SINGLE", 1, 1);
        seedOption(groupId, "fresh_" + suffix(), "Fresh Milk");
        jdbc.update("insert into catalog.product_customization_groups (product_id, group_id, required, "
                + "display_order) values (?,?,false,0)", l.productId, groupId);
        Session session = register();

        Resp failed = add(session, null, body(l.productId, 1));
        assertEquals(400, failed.status(), failed.body());
        assertEquals("VALIDATION_ERROR", failed.errorCode());
        assertEquals(0, dec("0").compareTo(reserved(l.beans)));
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=? and cart_item_id is not null", Long.class, storeId));
    }

    @Test
    void anAnonymousCallerIsRejected() {
        Latte l = latte();
        Resp anonymous = send(HttpMethod.POST, ITEMS, body(l.productId, 1), null, null);
        assertEquals(401, anonymous.status(), "cart mutation requires authentication");
        assertEquals(0, dec("0").compareTo(reserved(l.beans)));
    }

    @Test
    void aStoreThatDoesNotOfferTheProductIsRejected() throws Exception {
        Latte l = latte();
        UUID otherStore = seedStore();
        Session session = register();
        String body = "{\"storeId\":\"" + otherStore + "\",\"productId\":\"" + l.productId
                + "\",\"quantity\":1,\"customizations\":[]}";

        Resp failed = add(session, null, body);
        assertEquals(404, failed.status(), failed.body());
        assertEquals("Product is not offered by this store", failed.errorMessage());
        assertEquals(0, dec("0").compareTo(reserved(l.beans)));
    }

    // ------------------------------------------------------------- HTTP client

    private record Session(String token, String userId) {
    }

    private record Resp(int status, String body) {

        JsonNode data() {
            return tree().path("data");
        }

        String errorCode() {
            return tree().path("error").path("code").asText();
        }

        String errorMessage() {
            return tree().path("error").path("message").asText();
        }

        private JsonNode tree() {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Unparseable response: " + body, e);
            }
        }
    }

    private Resp add(Session session, String idempotencyKey, String body) {
        return send(HttpMethod.POST, ITEMS, body, session.token(), idempotencyKey);
    }

    private Resp send(HttpMethod method, String path, String json, String bearer, String idempotencyKey) {
        // java.net.http, not RestTemplate: the JDK client is the only one on the
        // classpath that speaks PATCH out of the box, and the cart quantity update
        // is a PATCH.
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
        String email = "cart-http-" + UUID.randomUUID() + "@smart.coffee";
        Resp response = send(HttpMethod.POST, "/api/v1/auth/register",
                mapper.writeValueAsString(new RegisterRequest("Cart Http Customer", email, "Str0ng!Pass", "en")),
                null, null);
        assertEquals(201, response.status(), "register: " + response.body());
        return new Session(response.data().path("accessToken").asText(),
                response.data().path("userId").asText());
    }

    private String body(UUID productId, int quantity) {
        return "{\"storeId\":\"" + storeId + "\",\"productId\":\"" + productId + "\",\"quantity\":"
                + quantity + ",\"customizations\":[]}";
    }

    // ------------------------------------------------------------- assertions

    private BigDecimal reserved(UUID ingredient) {
        return jdbc.queryForObject("select reserved_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private long ledgerHeaders(String cartId, String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where cart_id=? and transaction_type=?", Long.class, UUID.fromString(cartId), type);
    }

    private void assertNoConsumption() {
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_CONSUMPTION'", Long.class, storeId));
    }

    // --------------------------------------------------------------- fixtures

    private record Latte(UUID productId, UUID beans, UUID milk) {
    }

    private Latte latte() {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID productId = UUID.randomUUID();
        String code = "HTTP_LATTE_" + suffix();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, true, true, now(), now(), 0)",
                productId, code, category, "IT " + code, dec("10"));
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", storeId, productId);

        UUID beans = seedIngredient("HTTP_BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("HTTP_MILK_" + suffix(), GRAM);
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
        return new Latte(productId, beans, milk);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Cart Http Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "HTTP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedGroup(String code, String selectionType, int min, Integer max) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_groups (group_id, group_code, group_name, selection_type, "
                + "minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,?,1,true,now(),now(),0)", id, code + "_" + suffix(), code, selectionType, min,
                max);
        return id;
    }

    private UUID seedOption(UUID groupId, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_options (option_id, group_id, option_code, option_name, "
                + "additional_price, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,0,0,true,now(),now(),0)", id, groupId, code, name);
        return id;
    }

    private CurrentActor seedAdmin() {
        UUID id = UUID.randomUUID();
        String email = "cart-http-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Cart Http Admin", email, email, "en-US");
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
                + "reserved_stock, minimum_stock, average_unit_cost) values (?,?,1000,0,0,0.025)", storeId,
                ingredient);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
