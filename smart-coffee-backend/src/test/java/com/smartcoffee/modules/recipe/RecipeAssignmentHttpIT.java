package com.smartcoffee.modules.recipe;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.modules.auth.api.RegisterRequest;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Slice 2B.1 HTTP-level verification of the recipe ASSIGNMENT surface on the
 * live stack (RANDOM_PORT, real security filter chain, Flyway V001..V044).
 * Follows the project's plain-RestTemplate convention
 * ({@code CustomerRegistrationAndSecurityIT}). Auth: register over HTTP, then
 * grant SUPER_ADMIN via SQL — {@code CurrentActorProvider} re-resolves
 * permissions per request from the DB, so the same bearer token picks the
 * grants up without re-login.
 *
 * <p>Covers the spec's HTTP requirements: assign/read/remove the product
 * fallback and the variant override, cross-product rejection (400), draft
 * rejection (400), stale optimistic-lock versions (409), archive-in-use (409)
 * and the permission floor (401/403).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecipeAssignmentHttpIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String BASE = "/api/v1/management/catalog";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private final RestTemplate rest = new RestTemplate();
    private static final ObjectMapper mapper = new ObjectMapper();

    private record Resp(int status, String body) {

        JsonNode data() {
            try {
                return mapper.readTree(body).path("data");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Resp send(HttpMethod method, String path, String json, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        try {
            ResponseEntity<String> r = rest.exchange("http://localhost:" + port + path, method,
                    new HttpEntity<>(json, h), String.class);
            return new Resp(r.getStatusCode().value(), r.getBody());
        } catch (RestClientResponseException ex) {
            return new Resp(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        }
    }

    private String json(Object body) {
        try {
            return body == null ? null : mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Registers over HTTP and elevates the account to SUPER_ADMIN; returns the bearer token. */
    private String adminToken() {
        String email = "recipe-http-" + UUID.randomUUID() + "@smart.coffee";
        Resp r = send(HttpMethod.POST, "/api/v1/auth/register",
                json(new RegisterRequest("Recipe Http Admin", email, "Str0ng!Pass", "en")), null);
        assertEquals(201, r.status(), "register should return 201: " + r.body());
        String token = r.data().path("accessToken").asText();
        String userId = r.data().path("userId").asText();
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?::uuid,null,now())", UUID.randomUUID(),
                UUID.fromString(userId), SUPER_ADMIN);
        return token;
    }

    private String[] createAndActivateRecipe(String token, UUID productId, UUID ingredient, String qty) {
        String draftJson = "{\"yieldQuantity\":1,\"notes\":\"http draft\",\"items\":[{"
                + "\"ingredientId\":\"" + ingredient + "\",\"quantityRequiredBase\":" + qty
                + ",\"wastagePercent\":0,\"scalingRule\":\"FIXED\",\"displayOrder\":0,\"optional\":false}]}";
        Resp d = send(HttpMethod.POST, BASE + "/products/" + productId + "/recipes", draftJson, token);
        assertEquals(200, d.status(), "create draft: " + d.body());
        String versionId = d.data().path("recipeVersionId").asText();
        long version = d.data().path("version").asLong();
        Resp a = send(HttpMethod.POST,
                BASE + "/recipes/" + versionId + "/activate?expectedVersion=" + version, null, token);
        assertEquals(200, a.status(), "activate: " + a.body());
        assertEquals("ACTIVE", a.data().path("status").asText());
        return new String[] { versionId, String.valueOf(a.data().path("version").asLong()) };
    }

    private long productVersion(UUID productId) {
        return jdbc.queryForObject("select version from catalog.products where product_id = ?",
                Long.class, productId);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void assignReadAndRemoveProductFallbackOverHttp() {
        String token = adminToken();
        UUID productId = seedProduct("HTTP_FB_" + suffix());
        UUID ingredient = seedIngredient("HTTP_ING_" + suffix());
        String[] active = createAndActivateRecipe(token, productId, ingredient, "0.5");

        // Published but unassigned: resolution is NONE — assignment selects.
        Resp none = send(HttpMethod.GET, BASE + "/products/" + productId + "/recipes/resolve", null, token);
        assertEquals(200, none.status());
        assertEquals("NONE", none.data().path("source").asText());

        Resp assign = send(HttpMethod.PUT, BASE + "/products/" + productId + "/recipe",
                "{\"recipeVersionId\":\"" + active[0] + "\",\"expectedProductVersion\":"
                        + productVersion(productId) + "}", token);
        assertEquals(200, assign.status(), "assign fallback: " + assign.body());
        assertEquals(active[0], assign.data().path("activeRecipeVersionId").asText());

        Resp resolved = send(HttpMethod.GET, BASE + "/products/" + productId + "/recipes/resolve", null, token);
        assertEquals(200, resolved.status());
        assertEquals("PRODUCT", resolved.data().path("source").asText());
        assertEquals(active[0], resolved.data().path("recipeVersion").path("recipeVersionId").asText());

        // Remove the fallback -> NONE again.
        Resp clear = send(HttpMethod.PUT, BASE + "/products/" + productId + "/recipe",
                "{\"recipeVersionId\":null,\"expectedProductVersion\":" + productVersion(productId) + "}", token);
        assertEquals(200, clear.status(), "clear fallback: " + clear.body());
        assertTrue(clear.data().path("activeRecipeVersionId").isNull());
        assertEquals("NONE", send(HttpMethod.GET, BASE + "/products/" + productId + "/recipes/resolve",
                null, token).data().path("source").asText());
    }

    @Test
    void staleProductVersionAndForeignAndDraftAssignmentsAreRejectedOverHttp() {
        String token = adminToken();
        UUID productId = seedProduct("HTTP_REJ_" + suffix());
        UUID otherProduct = seedProduct("HTTP_REJ2_" + suffix());
        UUID ingredient = seedIngredient("HTTP_ING_" + suffix());

        // DRAFT (never activated) cannot be assigned.
        Resp d = send(HttpMethod.POST, BASE + "/products/" + productId + "/recipes",
                "{\"yieldQuantity\":1,\"items\":[{\"ingredientId\":\"" + ingredient
                        + "\",\"quantityRequiredBase\":1,\"wastagePercent\":0,\"scalingRule\":\"FIXED\","
                        + "\"displayOrder\":0,\"optional\":false}]}", token);
        assertEquals(200, d.status(), d.body());
        String draftId = d.data().path("recipeVersionId").asText();
        Resp draftAssign = send(HttpMethod.PUT, BASE + "/products/" + productId + "/recipe",
                "{\"recipeVersionId\":\"" + draftId + "\",\"expectedProductVersion\":"
                        + productVersion(productId) + "}", token);
        assertEquals(400, draftAssign.status(), draftAssign.body());
        assertTrue(draftAssign.body().contains("RECIPE_VERSION_NOT_ACTIVE"));

        // A version owned by ANOTHER product is refused with the product-scoped code.
        String[] otherActive = createAndActivateRecipe(token, otherProduct, ingredient, "1");
        Resp foreign = send(HttpMethod.PUT, BASE + "/products/" + productId + "/recipe",
                "{\"recipeVersionId\":\"" + otherActive[0] + "\",\"expectedProductVersion\":"
                        + productVersion(productId) + "}", token);
        assertEquals(400, foreign.status(), foreign.body());
        assertTrue(foreign.body().contains("PRODUCT_RECIPE_MISMATCH"));

        // Stale optimistic-lock echo -> 409.
        Resp stale = send(HttpMethod.PUT, BASE + "/products/" + productId + "/recipe",
                "{\"recipeVersionId\":\"" + otherActive[0] + "\",\"expectedProductVersion\":999}", token);
        assertEquals(409, stale.status(), stale.body());
        assertTrue(stale.body().contains("PRODUCT_VERSION_CONFLICT"));
    }

    @Test
    void archiveBlockedWhileAssignedThenAllowedOverHttp() {
        String token = adminToken();
        UUID productId = seedProduct("HTTP_ARC_" + suffix());
        UUID ingredient = seedIngredient("HTTP_ING_" + suffix());
        String[] active = createAndActivateRecipe(token, productId, ingredient, "2");

        send(HttpMethod.PUT, BASE + "/products/" + productId + "/recipe",
                "{\"recipeVersionId\":\"" + active[0] + "\",\"expectedProductVersion\":"
                        + productVersion(productId) + "}", token);

        Resp blocked = send(HttpMethod.POST,
                BASE + "/recipes/" + active[0] + "/archive?expectedVersion=" + active[1], null, token);
        assertEquals(409, blocked.status(), blocked.body());
        assertTrue(blocked.body().contains("RECIPE_VERSION_IN_USE"),
                "assigned version must refuse archive with the in-use code");

        send(HttpMethod.PUT, BASE + "/products/" + productId + "/recipe",
                "{\"recipeVersionId\":null,\"expectedProductVersion\":" + productVersion(productId) + "}", token);
        String nowVersion = send(HttpMethod.GET, BASE + "/recipes/" + active[0], null, token)
                .data().path("version").asText();
        Resp ok = send(HttpMethod.POST,
                BASE + "/recipes/" + active[0] + "/archive?expectedVersion=" + nowVersion, null, token);
        assertEquals(200, ok.status(), ok.body());
        assertEquals("ARCHIVED", ok.data().path("status").asText());
    }

    @Test
    void variantOverrideAssignResolveAndRemoveOverHttp() {
        String token = adminToken();
        UUID productId = seedProduct("HTTP_OV_" + suffix());
        UUID ingredient = seedIngredient("HTTP_ING_" + suffix());
        // Product fallback (tier 2) and a DISTINCT variant override (tier 1).
        String[] fallback = createAndActivateRecipe(token, productId, ingredient, "180");
        send(HttpMethod.PUT, BASE + "/products/" + productId + "/recipe",
                "{\"recipeVersionId\":\"" + fallback[0] + "\",\"expectedProductVersion\":"
                        + productVersion(productId) + "}", token);
        String[] override = createAndActivateRecipe(token, productId, ingredient, "240");

        UUID variantId = seedVariant(productId);
        long vVersion = jdbc.queryForObject(
                "select version from catalog.product_variants where variant_id = ?", Long.class, variantId);
        Resp assign = send(HttpMethod.PUT, BASE + "/variants/" + variantId + "/recipe",
                "{\"recipeVersionId\":\"" + override[0] + "\",\"expectedVariantVersion\":" + vVersion + "}", token);
        assertEquals(200, assign.status(), "assign override: " + assign.body());
        assertEquals(override[0], assign.data().path("activeRecipeVersionId").asText());

        Resp resolved = send(HttpMethod.GET,
                BASE + "/products/" + productId + "/variants/" + variantId + "/recipe/resolve", null, token);
        assertEquals(200, resolved.status());
        assertEquals("VARIANT", resolved.data().path("source").asText());
        assertEquals(override[0], resolved.data().path("recipeVersion").path("recipeVersionId").asText());

        // Removing the override restores the product fallback through the same API.
        long vVersion2 = jdbc.queryForObject(
                "select version from catalog.product_variants where variant_id = ?", Long.class, variantId);
        Resp clear = send(HttpMethod.PUT, BASE + "/variants/" + variantId + "/recipe",
                "{\"recipeVersionId\":null,\"expectedVariantVersion\":" + vVersion2 + "}", token);
        assertEquals(200, clear.status(), clear.body());
        Resp restored = send(HttpMethod.GET,
                BASE + "/products/" + productId + "/variants/" + variantId + "/recipe/resolve", null, token);
        assertEquals("PRODUCT", restored.data().path("source").asText());
        assertEquals(fallback[0], restored.data().path("recipeVersion").path("recipeVersionId").asText());
    }

    @Test
    void anonymousIsUnauthorizedAndPlainCustomerIsForbidden() {
        String token = adminToken();
        UUID productId = seedProduct("HTTP_AUTHZ_" + suffix());

        Resp anon = send(HttpMethod.GET, BASE + "/products/" + productId + "/recipes/resolve", null, null);
        assertEquals(401, anon.status(), "no bearer must be rejected at the filter");

        // Fresh registration WITHOUT any role elevation: CUSTOMER only.
        String email = "plain-" + UUID.randomUUID() + "@smart.coffee";
        Resp reg = send(HttpMethod.POST, "/api/v1/auth/register",
                json(new RegisterRequest("Plain Customer", email, "Str0ng!Pass", "en")), null);
        assertEquals(201, reg.status(), reg.body());
        String customer = reg.data().path("accessToken").asText();

        Resp forbiddenView = send(HttpMethod.GET, BASE + "/products/" + productId + "/recipes/resolve",
                null, customer);
        assertEquals(403, forbiddenView.status(), "recipe:view is not a customer permission");
        Resp forbiddenAssign = send(HttpMethod.PUT, BASE + "/products/" + productId + "/recipe",
                "{\"recipeVersionId\":null,\"expectedProductVersion\":" + productVersion(productId) + "}",
                customer);
        assertEquals(403, forbiddenAssign.status(), "recipe:assign is not a customer permission");

        // The admin path in the same run still works (no collateral lockout).
        assertEquals(200, send(HttpMethod.GET, BASE + "/products/" + productId + "/recipes/resolve",
                null, token).status());
    }

    // ------------------------------------------------------------- raw seeding

    private UUID seedProduct(String code) {
        UUID cat = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, false, true, now(), now(), 0)",
                id, code, cat, "IT " + code, new BigDecimal("10"));
        return id;
    }

    private UUID seedIngredient(String code) {
        UUID uom = jdbc.queryForObject("select unit_id from inventory.units_of_measure limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, base_unit_id, "
                + "standard_cost, track_inventory, track_batches, active, created_at, updated_at, version) "
                + "values (?,?,?,?, 0, false, false, true, now(), now(), 0)",
                id, code + "_" + suffix(), code, uom);
        return id;
    }

    private UUID seedVariant(UUID productId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.product_variants (variant_id, product_id, variant_code, variant_name, "
                + "price, currency_code, is_default, active, display_order, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', false, true, 0, now(), now(), 0)",
                id, productId, "V_" + suffix(), "Large", new BigDecimal("14"));
        return id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
