package com.smartcoffee.modules.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestTemplate;

/**
 * Checkpoint 6 — OpenAPI contract gate. Boots the real application on a random
 * port and issues a genuine HTTP GET to the springdoc document, asserting every
 * endpoint added across checkpoints CP3-CP5 is actually published (not just
 * compiled). Guards against a controller silently missing from the handoff spec
 * the frontends are built against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractIT extends IntegrationTestBase {

    @LocalServerPort
    int port;

    private final RestTemplate rest = new RestTemplate();

    private String docs() {
        String body = rest.getForObject("http://localhost:" + port + "/v3/api-docs", String.class);
        assertNotNull(body, "springdoc /v3/api-docs must be reachable without auth");
        return body;
    }

    @Test
    void publishesAccessContextAndBranchManagement() {
        var d = docs();
        assertTrue(d.contains("/api/v1/users/me/access-context"), "access-context missing");
        assertTrue(d.contains("/api/v1/management/stores"), "branch list missing");
        assertTrue(d.contains("/api/v1/management/stores/{storeId}/status"), "branch status missing");
    }

    @Test
    void publishesBranchProductManagement() {
        var d = docs();
        assertTrue(d.contains("/api/v1/management/stores/{storeId}/store-products"), "branch-product list missing");
        assertTrue(d.contains("/api/v1/management/stores/{storeId}/store-products/{productId}/configuration"),
                "branch-product configuration update missing");
    }

    @Test
    void publishesProductionStationDiscoveryAndQueue() {
        var d = docs();
        assertTrue(d.contains("/api/v1/management/stores/{storeId}/production/stations"), "station discovery missing");
        assertTrue(d.contains("/api/v1/stores/{storeId}/production/{station}"), "production queue missing");
        assertTrue(d.contains("/api/v1/production/ticket-items/{itemId}/start"), "ticket start missing");
    }

    @Test
    void publishesCurrencyConversionPreviewAndCashPayment() {
        var d = docs();
        assertTrue(d.contains("/api/v1/currency-conversions/preview"), "conversion preview missing");
        assertTrue(d.contains("/api/v1/payments/cash"), "cash payment missing");
    }

    @Test
    void cashPaymentExchangeRateIsDeprecatedAndConversionSchemaPublished() {
        var d = docs();
        // 5D payment conversion snapshot must be part of the public contract.
        assertTrue(d.contains("PaymentConversionResponse"), "payment conversion response schema missing");
        assertTrue(d.contains("tenderChangeAmount"), "conversion snapshot fields missing");
        assertTrue(d.contains("CurrencyConversionResponse"), "conversion preview schema missing");
        // 5C: the deprecated client rate is still exposed but flagged deprecated.
        assertTrue(d.contains("\"exchangeRate\""), "expected exchangeRate field missing from contract");
        assertTrue(d.contains("\"deprecated\""), "deprecated flag missing from contract");
    }

    // ------------------------------------------------------------------
    // Phase 9 (Master Prompt §13): every administration endpoint added by this
    // program must be published WITH a real summary and declared error
    // statuses, and the document must leak neither JPA entities nor
    // security-sensitive fields.
    // ------------------------------------------------------------------

    private static final List<String> ADMIN_PATHS = List.of(
            "/api/v1/management/staff",
            "/api/v1/management/staff/{userId}",
            "/api/v1/management/staff/{userId}/status",
            "/api/v1/management/staff/{userId}/role-assignments",
            "/api/v1/management/staff/{userId}/role-assignments/{assignmentId}/revoke",
            "/api/v1/management/roles",
            "/api/v1/management/roles/{roleId}",
            "/api/v1/management/roles/{roleId}/permissions",
            "/api/v1/management/permissions",
            "/api/v1/management/orders",
            "/api/v1/management/orders/{orderId}",
            "/api/v1/management/orders/{orderId}/cancel",
            "/api/v1/management/orders/{orderId}/complete",
            "/api/v1/management/users",
            "/api/v1/management/users/{userId}",
            "/api/v1/stores/{storeId}/inventory/adjustments",
            "/api/v1/stores/{storeId}/inventory/movements",
            "/api/v1/management/promotions",
            "/api/v1/management/promotions/{promotionId}",
            "/api/v1/management/promotions/{promotionId}/status",
            "/api/v1/management/reports/sales-summary",
            "/api/v1/management/reports/inventory-cogs",
            "/api/v1/management/exchange-rates",
            "/api/v1/management/exchange-rates/{rateId}",
            "/api/v1/management/exchange-rates/{rateId}/deactivate",
            "/api/v1/users/me",
            "/api/v1/users/me/access-context");

    private JsonNode doc() {
        try {
            return new ObjectMapper().readTree(docs());
        } catch (Exception e) {
            fail("/v3/api-docs must be valid JSON: " + e.getMessage());
            return null;
        }
    }

    @Test
    void publishesEveryAdministrationEndpoint() {
        JsonNode paths = doc().path("paths");
        for (String p : ADMIN_PATHS) {
            assertTrue(paths.has(p), "missing published path: " + p);
        }
    }

    @Test
    void everyAdministrationOperationHasRealSummary() {
        JsonNode paths = doc().path("paths");
        for (String p : ADMIN_PATHS) {
            JsonNode path = paths.path(p);
            for (Iterator<Map.Entry<String, JsonNode>> it = path.properties().iterator(); it.hasNext();) {
                Map.Entry<String, JsonNode> op = it.next();
                assertTrue(op.getValue().path("summary").asText("").length() >= 8,
                        p + " " + op.getKey() + " has no meaningful summary");
                // §13: authentication must be documented on every operation.
                assertTrue(op.getValue().path("description").asText("").contains("Authentication"),
                        p + " " + op.getKey() + " does not document authentication");
            }
        }
    }

    @Test
    void guardedMutationsDeclareConflictAndForbiddenStatuses() {
        JsonNode paths = doc().path("paths");
        // Stale-version and permission refusals are real production statuses;
        // they must be part of the published contract, not folklore.
        assertStatus(paths, "/api/v1/management/staff/{userId}", "put", "409");
        assertStatus(paths, "/api/v1/management/staff/{userId}/status", "post", "409");
        assertStatus(paths, "/api/v1/management/staff", "post", "409");
        assertStatus(paths, "/api/v1/management/roles/{roleId}/permissions", "put", "403");
        assertStatus(paths, "/api/v1/management/promotions", "post", "409");
        assertStatus(paths, "/api/v1/management/promotions/{promotionId}", "put", "409");
        assertStatus(paths, "/api/v1/management/promotions/{promotionId}/status", "post", "400");
        assertStatus(paths, "/api/v1/management/orders", "get", "403");
        assertStatus(paths, "/api/v1/management/orders/{orderId}", "get", "404");
        // Full-stack wiring §1.1–§1.5: real error codes are contract, not folklore.
        assertStatus(paths, "/api/v1/management/orders/{orderId}/cancel", "post", "409");
        assertStatus(paths, "/api/v1/management/orders/{orderId}/cancel", "post", "403");
        assertStatus(paths, "/api/v1/management/orders/{orderId}/complete", "post", "409");
        assertStatus(paths, "/api/v1/management/users", "get", "403");
        assertStatus(paths, "/api/v1/management/users/{userId}", "get", "404");
        assertStatus(paths, "/api/v1/stores/{storeId}/inventory/adjustments", "post", "409");
        assertStatus(paths, "/api/v1/stores/{storeId}/inventory/movements", "get", "403");
        assertStatus(paths, "/api/v1/management/reports/sales-summary", "get", "400");
        assertStatus(paths, "/api/v1/management/stores", "post", "409");
        assertStatus(paths, "/api/v1/management/exchange-rates/{rateId}/deactivate", "post", "409");
        assertStatus(paths, "/api/v1/users/me/access-context", "get", "401");
    }

    private void assertStatus(JsonNode paths, String path, String verb, String status) {
        JsonNode op = paths.path(path).path(verb);
        assertFalse(op.isMissingNode(), path + " " + verb + " missing");
        assertTrue(op.path("responses").has(status),
                path + " " + verb + " does not declare " + status + " although the service can produce it");
    }

    @Test
    void publishesCustomerOrderHistoryAndReceiptPaths() {
        // §1.2/§1.3 live on the customer-facing namespace (self-scoped reads),
        // so they are not in ADMIN_PATHS; the gate is publication + documented
        // authentication.
        JsonNode paths = doc().path("paths");
        for (String p : List.of("/api/v1/orders", "/api/v1/orders/{orderId}/receipt")) {
            assertTrue(paths.has(p), "missing published customer path: " + p);
            for (Iterator<Map.Entry<String, JsonNode>> it = paths.path(p).properties().iterator(); it.hasNext();) {
                Map.Entry<String, JsonNode> op = it.next();
                assertTrue(op.getValue().path("description").asText("").contains("Authentication"),
                        p + " " + op.getKey() + " does not document authentication");
            }
        }
        assertStatus(paths, "/api/v1/orders/{orderId}/receipt", "get", "404");
    }

    @Test
    void documentLeaksNoJpaEntitiesOrSensitiveFields() {
        String d = docs();
        for (String sensitive : List.of("passwordHash", "refreshTokenHash", "tokenHash", "secretKey",
                "providerCredential", "otpCode")) {
            assertFalse(d.contains("\"" + sensitive + "\""), "sensitive field in public document: " + sensitive);
        }
        // No JPA entity may be published directly: every admin response must
        // be an api-DTO (StaffResponse, RoleResponse, OrderItemResponse, ...).
        JsonNode schemas = doc().path("components").path("schemas");
        for (String name : List.of("User", "Role", "Permission", "Order", "Store", "ExchangeRate",
                "UserRoleAssignment", "Promotion")) {
            assertFalse(schemas.has(name), "JPA entity schema published directly: " + name);
        }
    }
}
