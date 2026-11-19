package com.smartcoffee.modules.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.modules.auth.security.JwtTokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

/**
 * Slice 3A management preview HTTP tests (spec §24) on the real stack: RANDOM_PORT,
 * the production JWT filter chain and Flyway V001..V047. Authorization is proven
 * end-to-end from seeded RBAC rows (SUPER_ADMIN global; STORE_MANAGER store-scoped),
 * not a fabricated actor. Also carries the §26 no-write guarantee: the preview endpoint
 * mutates no inventory / reservation / ledger / cart / order / reconciliation table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AvailabilityManagementHttpIT extends AvailabilityITBase {

    @LocalServerPort
    int port;
    @Autowired
    JwtTokenService tokens;

    private final HttpClient client = HttpClient.newHttpClient();

    private String adminToken;   // SUPER_ADMIN, global
    private String managerBToken; // STORE_MANAGER scoped to an unrelated store
    private String customerToken;
    private UUID storeB;

    @BeforeEach
    void setUpHttp() {
        storeId = seedStore();
        adminToken = staffToken(SUPER_ADMIN, null);
        storeB = seedStore();
        managerBToken = staffToken(STORE_MANAGER, storeB);
        customerToken = staffToken(UUID.fromString(
                jdbc.queryForObject("select role_id from identity.roles where role_code='CUSTOMER'", String.class)),
                null);
    }

    // ----------------------------------------------------------------- §24 happy

    @Test
    void availableProductReturns200Orderable() {
        UUID productId = inStockProduct();
        Resp r = get(url(storeId, productId, null), adminToken);
        assertEquals(200, r.status(), r.body());
        assertTrue(r.data().path("orderable").asBoolean(), r.body());
        assertTrue(r.data().path("inStock").asBoolean());
        assertFalse(r.data().path("reasonCodes").toString().contains("PRODUCT_OUT_OF_STOCK"));
    }

    @Test
    void unavailableProductReturns200WithReasonCodes() {
        UUID productId = seedProduct("OOS_" + suffix());
        offerProduct(storeId, productId);
        UUID ing = seedIngredient("OOSING", GRAM);
        seedBalance(storeId, ing, "0");
        assignProductRecipe(productId, "o", List.of(item(ing, "18", 0)));

        Resp r = get(url(storeId, productId, null), adminToken);
        assertEquals(200, r.status(), "normal unavailability is a 200, not an error: " + r.body());
        assertFalse(r.data().path("orderable").asBoolean());
        assertTrue(r.data().path("reasonCodes").toString().contains("PRODUCT_OUT_OF_STOCK"), r.body());
    }

    @Test
    void variantAndOptionDetailAreIncluded() {
        UUID productId = seedProduct("VD_" + suffix());
        offerProduct(storeId, productId);
        UUID beans = seedIngredient("BEANSVD", GRAM);
        seedBalance(storeId, beans, "1000");
        assignProductRecipe(productId, "vd", List.of(item(beans, "18", 0)));
        createVariant(productId, "REG", true);
        UUID sugarGroup = seedGroup(productId, "SUGARVD", "Sugar", "SINGLE", 1, 1);
        seedOption(sugarGroup, "S0", "0%", dec("0"), "MULTIPLY", beans, null, null, dec("0"));

        Resp r = get(url(storeId, productId, null), adminToken);
        assertEquals(200, r.status(), r.body());
        assertTrue(r.data().path("variants").isArray() && r.data().path("variants").size() >= 1,
                "variant results included: " + r.body());
        assertTrue(r.data().path("optionGroups").isArray() && r.data().path("optionGroups").size() >= 1,
                "option availability included: " + r.body());
    }

    @Test
    void includeOptionsFalseDropsOptionBreakdown() {
        UUID productId = seedProduct("NOOPT_" + suffix());
        offerProduct(storeId, productId);
        UUID beans = seedIngredient("BEANSNO", GRAM);
        seedBalance(storeId, beans, "1000");
        assignProductRecipe(productId, "no", List.of(item(beans, "18", 0)));
        UUID g = seedGroup(productId, "G", "Group", "SINGLE", 1, 1);
        seedOption(g, "A", "A", dec("0"), "MULTIPLY", beans, null, null, dec("0"));

        Resp with = get(url(storeId, productId, null), adminToken);
        Resp without = get(url(storeId, productId, null) + "?includeOptions=false", adminToken);
        assertEquals(200, without.status(), without.body());
        assertTrue(with.data().path("optionGroups").size() >= 1);
        assertEquals(0, without.data().path("optionGroups").size(), "options dropped from projection");
    }

    @Test
    void responsePreservesDecimalPrecisionOfPriceDelta() {
        UUID productId = seedProduct("PREC_" + suffix());
        offerProduct(storeId, productId);
        UUID ing = seedIngredient("INGPREC", GRAM);
        seedBalance(storeId, ing, "1000");
        assignProductRecipe(productId, "p", List.of(item(ing, "18", 0)));
        UUID g = seedGroup(productId, "PG", "PGroup", "SINGLE", 0, 2);
        seedOption(g, "ADD", "Add", dec("0.5"), "ADD", null, ing, dec("1"), null);

        Resp r = get(url(storeId, productId, null), adminToken);
        assertEquals(200, r.status(), r.body());
        assertTrue(r.body().contains("\"priceDelta\":0.5000"),
                "scale-4 decimal preserved through serialization: " + r.body());
    }

    // -------------------------------------------------------------------- §24 authz

    @Test
    void unauthenticatedReturns401() {
        Resp r = get(url(storeId, UUID.randomUUID(), null), null);
        assertEquals(401, r.status(), r.body());
    }

    @Test
    void customerWithoutPermissionReturns403() {
        UUID productId = inStockProduct();
        Resp r = get(url(storeId, productId, null), customerToken);
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void crossStoreRequestReturns403() {
        UUID productId = inStockProduct();
        // A manager scoped to storeB cannot preview the fixture store.
        Resp r = get(url(storeId, productId, null), managerBToken);
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void missingProductReturns404() {
        Resp r = get(url(storeId, UUID.randomUUID(), null), adminToken);
        assertEquals(404, r.status(), r.body());
        assertEquals("PRODUCT_NOT_FOUND", r.errorCode());
    }

    @Test
    void invalidQuantityReturns400() {
        UUID productId = inStockProduct();
        Resp r = get(url(storeId, productId, null) + "?quantity=0", adminToken);
        assertEquals(400, r.status(), r.body());
    }

    @Test
    void errorEnvelopeLeaksNoSqlOrStack() {
        Resp r = get(url(storeId, UUID.randomUUID(), null), adminToken);
        String lower = r.body().toLowerCase();
        assertFalse(lower.contains("stack"), r.body());
        assertFalse(lower.contains("select "), r.body());
        assertFalse(lower.contains("com.smartcoffee"), r.body());
    }

    @Test
    void responseExposesNoCostOrSupplierData() {
        UUID productId = inStockProduct();
        Resp r = get(url(storeId, productId, null), adminToken);
        String lower = r.body().toLowerCase();
        assertFalse(lower.contains("cost"), "no cost field: " + r.body());
        assertFalse(lower.contains("supplier"), "no supplier field: " + r.body());
    }

    // -------------------------------------------------------------------- §26 no-write

    @Test
    void previewEndpointWritesNothing() {
        UUID productId = inStockProduct();
        Snap before = snapshot();
        for (int i = 0; i < 5; i++) {
            Resp r = get(url(storeId, productId, null), adminToken);
            assertEquals(200, r.status(), r.body());
        }
        assertEquals(before, snapshot(), "the availability preview is strictly read-only");
    }

    // --------------------------------------------------------------------- fixtures

    /** A simple in-stock variant-less product with an assigned recipe; returns its id. */
    private UUID inStockProduct() {
        UUID productId = seedProduct("HTTP_" + suffix());
        offerProduct(storeId, productId);
        UUID ing = seedIngredient("HTTPING", GRAM);
        seedBalance(storeId, ing, "1000");
        assignProductRecipe(productId, "http", List.of(item(ing, "18", 0)));
        return productId;
    }

    private String url(UUID store, UUID product, UUID variant) {
        String base = "/api/v1/management/catalog/stores/" + store + "/products/" + product + "/availability";
        return variant == null ? base : base + "?variantId=" + variant;
    }

    // --------------------------------------------------------------- no-write snapshot

    private record Snap(long balances, long balanceVersionSum, long reservations, long reservationLines,
            long ledger, long carts, long cartItems, long orders, long orderItems, long recipeVersions,
            long recipeLines, long reconRuns, long reconFindings) {
    }

    private Snap snapshot() {
        return new Snap(
                cnt("select count(*) from inventory.inventory_balances"),
                lng("select coalesce(sum(version),0) from inventory.inventory_balances"),
                cnt("select count(*) from inventory.inventory_reservations"),
                cnt("select count(*) from inventory.inventory_reservation_lines"),
                cnt("select count(*) from inventory.stock_transactions"),
                cnt("select count(*) from cart.carts"),
                cnt("select count(*) from cart.cart_items"),
                cnt("select count(*) from ordering.orders"),
                cnt("select count(*) from ordering.order_items"),
                cnt("select count(*) from recipe.recipe_versions"),
                cnt("select count(*) from recipe.recipe_version_lines"),
                cnt("select count(*) from inventory.inventory_reconciliation_runs"),
                cnt("select count(*) from inventory.inventory_reconciliation_findings"));
    }

    private long cnt(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    private long lng(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    // ------------------------------------------------------------------ token seeding

    private String staffToken(UUID roleId, UUID store) {
        UUID userId = UUID.randomUUID();
        String email = "avail-http-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", userId, "Avail HTTP Actor", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), userId, roleId, store,
                java.sql.Timestamp.from(Instant.now()));
        return tokens.access(userId, 0L, java.util.Set.of());
    }

    private String staffToken(String roleId, UUID store) {
        return staffToken(UUID.fromString(roleId), store);
    }

    // --------------------------------------------------------------------- HTTP client

    private Resp get(String path, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(HttpMethod.GET.name(), HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling " + path, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("I/O failure calling " + path, e);
        }
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

    private static final ObjectMapper mapper = new ObjectMapper();
}
