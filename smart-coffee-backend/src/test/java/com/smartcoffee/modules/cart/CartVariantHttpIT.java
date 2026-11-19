package com.smartcoffee.modules.cart;

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
 * Slice 4B.1 §10 HTTP integration — the Variant identity and authoritative
 * price must actually cross the wire, and no client-supplied price may be
 * trusted.
 *
 * <p>Proves over real HTTP: POST add returns the Variant fields and the variant
 * price (ignoring any injected client price), GET current preserves the Variant
 * display on reload, DELETE removes the line, and an invalid Variant reference
 * returns a stable error rather than a silent fallback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CartVariantHttpIT extends IntegrationTestBase {

    private static final String CART_ITEMS = "/api/v1/carts/current/items";
    private static final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();
    private UUID storeId;
    private UUID productId;
    private UUID variantId;

    @BeforeEach
    void seedCatalog() {
        storeId = seedStore();
        productId = seedProduct("3.50");
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", storeId, productId);
        variantId = UUID.randomUUID();
        jdbc.update("insert into catalog.product_variants (variant_id, product_id, variant_code, variant_name, "
                + "price, currency_code, is_default, active, display_order, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', true, true, 0, now(), now(), 0)", variantId, productId,
                "VHTTP" + suffix(), "Large 16 oz", dec("4.50"));
    }

    @Test
    void addReturnsVariantIdentityAndTheAuthoritativeVariantPrice() throws Exception {
        String token = register();
        // A client-injected price/total must be ignored — the backend recomputes.
        Resp added = send(HttpMethod.POST, CART_ITEMS,
                "{\"storeId\":\"" + storeId + "\",\"productId\":\"" + productId + "\",\"variantId\":\"" + variantId
                        + "\",\"quantity\":1,\"unitPrice\":99.99,\"lineTotal\":99.99,\"customizations\":[]}",
                token, null);

        assertEquals(200, added.status(), added.body());
        JsonNode item = added.data().path("items").get(0);
        assertEquals(variantId.toString(), item.path("variantId").asText());
        assertEquals("Large 16 oz", item.path("variantName").asText());
        assertEquals(0, dec("4.50").compareTo(new BigDecimal(item.path("unitBasePrice").asText())),
                "variant price wins, injected client price ignored");
        assertEquals(0, dec("4.50").compareTo(new BigDecimal(item.path("variantUnitPrice").asText())));
        assertEquals(0, dec("4.50").compareTo(new BigDecimal(item.path("lineTotal").asText())));
    }

    @Test
    void reloadPreservesVariantDisplay() throws Exception {
        String token = register();
        send(HttpMethod.POST, CART_ITEMS, addBody(), token, null);

        Resp current = send(HttpMethod.GET, CART_ITEMS.replace("/items", "") + "?storeId=" + storeId, null, token, null);
        assertEquals(200, current.status(), current.body());
        JsonNode item = current.data().path("items").get(0);
        assertEquals("Large 16 oz", item.path("variantName").asText(), "reload reads the persisted snapshot");
        assertEquals(0, dec("4.50").compareTo(new BigDecimal(item.path("variantUnitPrice").asText())));
    }

    @Test
    void removeStillWorksOnAVariantLine() throws Exception {
        String token = register();
        Resp added = send(HttpMethod.POST, CART_ITEMS, addBody(), token, null);
        String cartId = added.data().path("cartId").asText();
        String itemId = added.data().path("items").get(0).path("cartItemId").asText();

        Resp removed = send(HttpMethod.DELETE, "/api/v1/carts/" + cartId + "/items/" + itemId, null, token, null);
        assertEquals(200, removed.status(), removed.body());
        assertEquals(0, removed.data().path("items").size());
    }

    @Test
    void invalidVariantReturnsAStableErrorAndAddsNothing() throws Exception {
        String token = register();
        Resp rejected = send(HttpMethod.POST, CART_ITEMS,
                "{\"storeId\":\"" + storeId + "\",\"productId\":\"" + productId + "\",\"variantId\":\""
                        + UUID.randomUUID() + "\",\"quantity\":1,\"customizations\":[]}",
                token, null);

        assertEquals(400, rejected.status(), rejected.body());
        assertEquals("VARIANT_NOT_FOUND", rejected.errorCode(), "no silent fallback to the product base");
    }

    // ------------------------------------------------------------- HTTP helpers

    private String addBody() {
        return "{\"storeId\":\"" + storeId + "\",\"productId\":\"" + productId + "\",\"variantId\":\"" + variantId
                + "\",\"quantity\":1,\"customizations\":[]}";
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

    private String register() throws Exception {
        String email = "variant-http-" + UUID.randomUUID() + "@smart.coffee";
        Resp response = send(HttpMethod.POST, "/api/v1/auth/register",
                mapper.writeValueAsString(new RegisterRequest("Variant Http Customer", email, "Str0ng!Pass", "en")),
                null, null);
        assertEquals(201, response.status(), "register: " + response.body());
        return response.data().path("accessToken").asText();
    }

    // ------------------------------------------------------------- raw seeding

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, '4B1 Http Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "VH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedProduct(String base) {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, true, true, now(), now(), 0)",
                id, "VHPROD_" + suffix(), category, "IT Variant Latte", dec(base));
        return id;
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
