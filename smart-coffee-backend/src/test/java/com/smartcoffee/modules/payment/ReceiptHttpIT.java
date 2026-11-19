package com.smartcoffee.modules.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.modules.auth.api.RegisterRequest;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wire contract for GET /api/v1/orders/{orderId}/receipt: the lifecycle status
 * is reported honestly (no download for PENDING), an issued receipt carries a
 * signed URL but never the object key or storage credentials, and another
 * customer cannot distinguish the order from a missing one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiptHttpIT extends IntegrationTestBase {

    private static final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectStorageService storage;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void pendingReceiptIsHonestThenIssuedReceiptCarriesOnlyASignedUrl() throws Exception {
        UUID store = seedStore();
        Session user = register();
        UUID orderId = insertOrder(store, user.customerId());
        UUID receiptId = insertReceipt(orderId, store, "PENDING");

        Resp pending = send(HttpMethod.GET, "/api/v1/orders/" + orderId + "/receipt", user.token());

        assertEquals(200, pending.status(), pending.body());
        JsonNode list = pending.data();
        assertEquals(1, list.size());
        JsonNode row = list.get(0);
        assertEquals(receiptId.toString(), row.path("receiptId").asText());
        assertEquals("PENDING", row.path("status").asText());
        assertTrue(row.path("downloadUrl").isNull(), "nothing is downloadable before issuance");
        assertTrue(row.path("failureReason").isNull());
        assertEquals(0, new BigDecimal("11.00").compareTo(new BigDecimal(row.path("snapshot").path("total").asText())));

        String key = "receipts/" + receiptId + ".txt";
        byte[] body = "SMART Coffee & Bakery Receipt\nTotal 11.00\n".getBytes(StandardCharsets.UTF_8);
        var metadata = storage.upload(key, "text/plain", body.length, new ByteArrayInputStream(body));
        jdbc.update("update payment.receipts set status = 'ISSUED', object_key = ?, mime_type = ?, size_bytes = ?, "
                + "checksum = ?, issued_at = now(), attempts = 1, last_attempt_at = now() where receipt_id = ?::uuid",
                key, "text/plain", metadata.sizeBytes(), metadata.checksum(), receiptId.toString());

        Resp issued = send(HttpMethod.GET, "/api/v1/orders/" + orderId + "/receipt", user.token());

        assertEquals(200, issued.status(), issued.body());
        JsonNode issuedRow = issued.data().get(0);
        assertEquals("ISSUED", issuedRow.path("status").asText());
        assertNotNull(issuedRow.path("downloadUrl").asText(null), "issued receipt exposes a download");
        assertTrue(issuedRow.path("downloadUrl").asText().contains("X-Amz-Signature"));
        assertEquals("text/plain", issuedRow.path("mimeType").asText());
        assertTrue(issuedRow.path("sizeBytes").asLong() > 0);
        assertNotNull(issuedRow.path("checksum").asText(null));
        assertEquals(0, new BigDecimal("11.00").compareTo(
                new BigDecimal(issuedRow.path("snapshot").path("total").asText())));

        String wire = issued.body();
        // The presigned URL necessarily carries the object path inside its
        // signature, but no separate metadata field may expose it and no
        // secret may appear anywhere in the payload.
        assertTrue(issuedRow.path("objectKey").isMissingNode(), "no raw objectKey field");
        assertTrue(issuedRow.path("object_key").isMissingNode(), "no raw object_key field");
        assertFalse(wire.toLowerCase().contains("secretkey"));
        assertFalse(wire.toLowerCase().contains("sql"));
        assertFalse(wire.toLowerCase().contains("constraint"));
    }

    @Test
    void strangerCannotReadAnotherCustomersReceipt() throws Exception {
        UUID store = seedStore();
        Session owner = register();
        UUID orderId = insertOrder(store, owner.customerId());
        insertReceipt(orderId, store, "PENDING");
        Session stranger = register();

        Resp denied = send(HttpMethod.GET, "/api/v1/orders/" + orderId + "/receipt", stranger.token());

        assertEquals(404, denied.status(), "another customer's receipt is indistinguishable from a missing one");
        String body = denied.body().toLowerCase();
        assertFalse(body.contains("sql"), body);
        assertFalse(body.contains("constraint"), body);
        assertFalse(body.contains("stack"), body);
        assertFalse(body.contains("receipts/"), body);
    }

    @Test
    void anonymousReadIsRejected() throws Exception {
        UUID store = seedStore();
        Session owner = register();
        UUID orderId = insertOrder(store, owner.customerId());
        insertReceipt(orderId, store, "PENDING");

        Resp anonymous = send(HttpMethod.GET, "/api/v1/orders/" + orderId + "/receipt", null);

        assertEquals(401, anonymous.status(), anonymous.body());
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

    private Resp send(HttpMethod method, String path, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method.name(), HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
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
        String email = "receipt-http-" + UUID.randomUUID() + "@smart.coffee";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        new RegisterRequest("Receipt HTTP Customer", email, "Str0ng!Pass", "en"))))
                .build();
        HttpResponse<String> registered = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, registered.statusCode(), registered.body());
        JsonNode data = mapper.readTree(registered.body()).path("data");
        UUID customerId = jdbc.queryForObject("select c.customer_id from customer.customer_profiles c "
                + "join identity.users u on u.user_id = c.user_id where u.normalized_email = ?", UUID.class, email);
        return new Session(data.path("accessToken").asText(), customerId);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?::uuid, ?, 'Receipt HTTP Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, "
                + "now(), now(), 0)", id.toString(),
                "RHP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID insertOrder(UUID storeId, UUID customerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, customer_id, source, "
                + "fulfillment_type, status, payment_status, currency_code, subtotal, total_amount, total_quantity, "
                + "ordered_at, updated_at) values (?::uuid, ?, ?::uuid, ?::uuid, 'CUSTOMER_WEB', 'PICKUP', "
                + "'CONFIRMED', 'PAID', 'USD', 11.00, 11.00, 1, now(), now())",
                id.toString(), "RHP-" + id.toString().substring(0, 8), storeId.toString(), customerId.toString());
        return id;
    }

    private UUID insertReceipt(UUID orderId, UUID storeId, String status) {
        UUID paymentId = UUID.randomUUID();
        jdbc.update("insert into payment.payments (payment_id, payment_reference, order_id, store_id, "
                + "payment_method_id, status, currency_code, amount, created_at, updated_at) "
                + "values (?::uuid, ?, ?::uuid, ?::uuid, '80000000-0000-0000-0000-000000000001', 'PENDING', 'USD', "
                + "11, now(), now())", paymentId.toString(), "RHPREF" + paymentId, orderId.toString(),
                storeId.toString());
        UUID receiptId = UUID.randomUUID();
        jdbc.update("insert into payment.receipts (receipt_id, receipt_number, payment_id, order_id, store_id, status, "
                + "receipt_snapshot, created_at) values (?::uuid, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?::jsonb, now())",
                receiptId.toString(), "RHR" + receiptId, paymentId.toString(), orderId.toString(),
                storeId.toString(), status, "{\"total\":\"11.00\"}");
        return receiptId;
    }
}
