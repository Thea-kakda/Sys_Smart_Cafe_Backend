package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartcoffee.modules.auth.security.JwtTokenService;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

/**
 * Slice 2E management API integration tests (spec §23) on the real HTTP stack:
 * RANDOM_PORT, the production JWT filter chain and Flyway V001..V047. Unlike the
 * service-level recovery/reconciliation suites — which hand-build a
 * {@link com.smartcoffee.common.security.CurrentActor} — every call here is made
 * with a bearer token for a user whose capabilities come from the SEEDED RBAC rows
 * (V047 grants {@code reservation:release} and {@code reconciliation:*} to
 * SUPER_ADMIN globally and to STORE_MANAGER for its own store). Authorization is
 * therefore proven end-to-end, not asserted against a fabricated actor.
 *
 * <p>The load-bearing HTTP guarantees checked here: unauthenticated → 401; a
 * permission/scope miss → 403 with NO mutation; a dry run reports but never moves
 * stock or the ledger; repair needs an explicit non-empty reason and is idempotent
 * (a stale finding is superseded, never double-applied); only an ACTIVE hold
 * releases — a consumed hold is refused; and the error envelope carries a stable
 * business code with no SQL or stack leak, while reservation detail exposes no
 * customer personal data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationManagementHttpIT extends ReservationOpsTestBase {

    @LocalServerPort
    int port;
    @Autowired
    JwtTokenService tokens;

    private final HttpClient client = HttpClient.newHttpClient();

    private String adminToken; // SUPER_ADMIN (global)
    private String managerAToken; // STORE_MANAGER scoped to {@code storeId} (the fixture store)
    private String managerBToken; // STORE_MANAGER scoped to an unrelated store
    private String customerToken; // CUSTOMER — none of the management permissions
    private UUID storeB;

    @BeforeEach
    void setUpManagement() {
        seedStoreAndCustomer();
        adminToken = staffToken(SUPER_ADMIN, null);
        managerAToken = staffToken(STORE_MANAGER, storeId);
        storeB = seedSecondStore();
        managerBToken = staffToken(STORE_MANAGER, storeB);
        customerToken = customerTokenFor();
    }

    // ------------------------------------------------------------------ §23 auth

    @Test
    void unauthenticatedCallIsRejected401() {
        Resp r = post("/api/v1/management/inventory/reconciliation/run", "{}", null);
        assertEquals(401, r.status(), r.body());
    }

    @Test
    void customerWithoutManagementPermissionIsRejected403AndNothingChanges() {
        Abandoned hold = abandoned();
        Resp r = adminPost("/api/v1/management/inventory/reconciliation/run",
                runBody(storeId, true), customerToken);
        assertEquals(403, r.status(), r.body());
        // A rejected scan mutates nothing: the hold is untouched, no ledger written.
        assertEquals("ACTIVE", reservationStatus(hold.resv()));
        assertEquals(0L, ledgerCount("SALE_RELEASE"), "a 403 scan releases nothing");
        assertEquals(0L, ledgerCount("SALE_CONSUMPTION"));
    }

    @Test
    void storeScopedManagerCannotPageAnotherStoreFindings() {
        Resp r = get("/api/v1/management/inventory/reconciliation?storeId=" + storeB, managerAToken);
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void storeScopedManagerCannotRequestGlobalScan() {
        // A null storeId is a global scan, which the service reserves for administrators.
        Resp r = adminPost("/api/v1/management/inventory/reconciliation/run", runBody(null, true), managerAToken);
        assertEquals(400, r.status(), "global scan for a store-only manager is refused: " + r.body());
    }

    // ------------------------------------------------------- §23 scan / dry run

    @Test
    void adminDryRunReportsAnomalyAndMutatesNothing() {
        Abandoned hold = abandoned();
        Resp r = adminPost("/api/v1/management/inventory/reconciliation/run", runBody(storeId, true), adminToken);
        assertEquals(200, r.status(), r.body());
        JsonNode data = r.data();
        assertTrue(data.path("dryRun").asBoolean(), "the request defaulted to a dry run");
        assertTrue(data.path("anomalyCount").asInt() >= 1, "the abandoned hold is reported: " + r.body());
        assertEquals(0, data.path("repairedCount").asInt(), "a dry run repairs nothing");
        // Nothing about the drifted hold moved.
        assertEquals("ACTIVE", reservationStatus(hold.resv()));
        assertEquals(0L, ledgerCount("SALE_RELEASE"), "a scan writes no release ledger");
        assertEquals(0L, ledgerCount("SALE_CONSUMPTION"), "a scan writes no consumption ledger");
        assertEquals(0, new java.math.BigDecimal("18").compareTo(reserved(hold.l().beans())));
    }

    @Test
    void adminFindingsPageIsWrappedWithPaginationMeta() {
        abandoned();
        Resp r = get("/api/v1/management/inventory/reconciliation?storeId=" + storeId + "&page=0&size=20", adminToken);
        assertEquals(200, r.status(), r.body());
        assertTrue(r.data().has("pagination"), "the list envelope exposes pagination metadata");
        assertTrue(r.data().has("items"), "the page carries an items array");
    }

    // ------------------------------------------------------------ §23 repair

    @Test
    void repairRequiresNonEmptyReason() {
        UUID runId = dryRunScan(storeId);
        Resp r = adminPost("/api/v1/management/inventory/reconciliation/" + runId + "/repair",
                "{\"dryRun\":false,\"reason\":\"\"}", adminToken);
        assertEquals(400, r.status(), "a blank reason is rejected: " + r.body());
    }

    @Test
    void repairReleasesTheAbandonedHoldAndReplayAppliesNothingTwice() {
        Abandoned hold = abandoned();
        UUID runId = dryRunScan(storeId);
        UUID idem = UUID.randomUUID();

        Resp first = adminPost("/api/v1/management/inventory/reconciliation/" + runId + "/repair",
                "{\"dryRun\":false,\"reason\":\"operator cleanup\"}", adminToken, idem);
        assertEquals(200, first.status(), first.body());
        assertTrue(first.data().path("repairedCount").asInt() >= 1, "the abandoned hold is repaired once");
        assertEquals("RELEASED", reservationStatus(hold.resv()));
        assertEquals(1L, ledgerCount("SALE_RELEASE", hold.resv()));

        // Same idempotency key, same run: the queue is now empty (already REPAIRED),
        // so the replay reports zero fresh repairs and does not release a second time.
        Resp replay = adminPost("/api/v1/management/inventory/reconciliation/" + runId + "/repair",
                "{\"dryRun\":false,\"reason\":\"operator cleanup\"}", adminToken, idem);
        assertEquals(200, replay.status(), replay.body());
        assertEquals(0, replay.data().path("repairedCount").asInt(), "a replay repairs nothing new");
        assertEquals(1L, ledgerCount("SALE_RELEASE", hold.resv()), "still exactly one release ledger");
    }

    @Test
    void staleFindingIsSupersededNotDoubleApplied() {
        Abandoned hold = abandoned();
        UUID runId = dryRunScan(storeId);
        // The worker finalizes the hold out from under the report before the operator repairs.
        jdbc.update("update inventory.inventory_reservations set status = 'RELEASED', released_at = now(), "
                + "release_reason = 'EXPIRED_RESERVATION' where reservation_id = ?", hold.resv());

        Resp r = adminPost("/api/v1/management/inventory/reconciliation/" + runId + "/repair",
                "{\"dryRun\":false,\"reason\":\"operator cleanup\"}", adminToken, UUID.randomUUID());
        assertEquals(200, r.status(), r.body());
        assertEquals(0, r.data().path("repairedCount").asInt(), "a stale finding is superseded, never applied");
        assertEquals(0L, ledgerCount("SALE_RELEASE", hold.resv()), "the repair pass adds no second ledger row");
    }

    // -------------------------------------------------------- §23 admin release

    @Test
    void adminReleasesValidActiveHold() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        long version = reservationVersion(resv);

        Resp r = adminPost("/api/v1/management/inventory/reservations/" + resv + "/release",
                "{\"expectedVersion\":" + version + ",\"reason\":\"operator cleanup\"}", adminToken, UUID.randomUUID());
        assertEquals(200, r.status(), r.body());
        assertTrue(r.data().path("released").asBoolean(), "the ACTIVE hold releases");
        assertEquals("RELEASED", reservationStatus(resv));
        assertEquals(1L, ledgerCount("SALE_RELEASE", resv));
    }

    @Test
    void consumedReservationCannotBeReleased() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        settle(order.orderId(), "20");
        assertEquals("CONSUMED", reservationStatus(resv));

        Resp r = adminPost("/api/v1/management/inventory/reservations/" + resv + "/release",
                "{\"reason\":\"too late\"}", adminToken, UUID.randomUUID());
        assertEquals(409, r.status(), r.body());
        assertEquals("RESERVATION_CONSUMED_CANNOT_RELEASE", r.errorCode());
        assertEquals("CONSUMED", reservationStatus(resv), "a refused release leaves the hold consumed");
    }

    @Test
    void releaseRejectsMissingReason() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());

        Resp r = adminPost("/api/v1/management/inventory/reservations/" + resv + "/release",
                "{\"expectedVersion\":" + reservationVersion(resv) + "}", adminToken, UUID.randomUUID());
        assertEquals(400, r.status(), "reason is @NotBlank: " + r.body());
        assertEquals("ACTIVE", reservationStatus(resv));
    }

    @Test
    void outOfScopeReservationCannotBeReleased() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());

        // A manager scoped to an unrelated store cannot reach this hold.
        Resp r = adminPost("/api/v1/management/inventory/reservations/" + resv + "/release",
                "{\"reason\":\"poach\"}", managerBToken, UUID.randomUUID());
        assertEquals(403, r.status(), r.body());
        assertEquals("ACTIVE", reservationStatus(resv));
        assertEquals(0L, ledgerCount("SALE_RELEASE", resv), "a rejected release mutates nothing");
    }

    // ---------------------------------------------------- §23 response safety

    @Test
    void errorEnvelopeCarriesNoSqlOrStackTrace() {
        Resp r = adminPost("/api/v1/management/inventory/reconciliation/" + UUID.randomUUID() + "/repair",
                "{\"dryRun\":false,\"reason\":\"nothing\"}", adminToken, UUID.randomUUID());
        assertEquals(404, r.status(), "an unknown run is a stable not-found: " + r.body());
        assertEquals("RECON_RUN_NOT_FOUND", r.errorCode());
        String lower = r.body().toLowerCase();
        assertFalse(lower.contains("stack"), "no stack trace in the envelope: " + r.body());
        assertFalse(lower.contains("select "), "no SQL in the envelope: " + r.body());
        assertFalse(lower.contains("com.smartcoffee"), "no internal class names in the envelope: " + r.body());
    }

    @Test
    void reservationDetailExposesNoCustomerPersonalData() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());

        Resp r = get("/api/v1/management/inventory/reservations/" + resv, adminToken);
        assertEquals(200, r.status(), r.body());
        assertEquals("ACTIVE", r.data().path("status").asText());
        // The detail projection is operational only — no customer identity leaks.
        String lower = r.body().toLowerCase();
        assertFalse(lower.contains("@smart.coffee"), "no customer email in the detail view");
        assertFalse(lower.contains("ops it customer"), "no customer name in the detail view");
    }

    // ------------------------------------------------------------------ fixtures

    private record Abandoned(UUID orderId, UUID resv, Latte l) {
    }

    /** A cancelled order whose ACTIVE hold was never released — the auto-repairable anomaly. */
    private Abandoned abandoned() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        jdbc.update("update ordering.orders set status = 'CANCELLED' where order_id = ?", order.orderId());
        return new Abandoned(order.orderId(), resv, l);
    }

    private UUID dryRunScan(UUID store) {
        Resp r = adminPost("/api/v1/management/inventory/reconciliation/run", runBody(store, true), adminToken);
        assertEquals(200, r.status(), r.body());
        return UUID.fromString(r.data().path("runId").asText());
    }

    private String runBody(UUID store, boolean dryRun) {
        return "{\"storeId\":" + (store == null ? "null" : "\"" + store + "\"") + ",\"dryRun\":" + dryRun + "}";
    }

    /** A second, empty store used only to prove a scoped manager cannot cross its boundary. */
    private UUID seedSecondStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Other Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "OTHER" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    // ------------------------------------------------------------- token seeding

    private String staffToken(String roleId, UUID store) {
        UUID userId = UUID.randomUUID();
        String email = "mgmt-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", userId, "Mgmt Actor", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), userId, UUID.fromString(roleId), store,
                java.sql.Timestamp.from(Instant.now()));
        return tokens.access(userId, 0L, java.util.Set.of());
    }

    private String customerTokenFor() {
        UUID userId = UUID.randomUUID();
        String email = "mgmt-cust-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", userId, "Mgmt Customer", email, email, "en-US");
        UUID customerId = UUID.randomUUID();
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", customerId, userId);
        UUID customerRole = jdbc.queryForObject("select role_id from identity.roles where role_code = 'CUSTOMER'",
                UUID.class);
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), userId, customerRole, null,
                java.sql.Timestamp.from(Instant.now()));
        return tokens.access(userId, 0L, java.util.Set.of());
    }

    // ---------------------------------------------------------------- HTTP client

    private Resp get(String path, String bearer) {
        return send(HttpMethod.GET, path, null, bearer, null);
    }

    private Resp post(String path, String json, String bearer) {
        return send(HttpMethod.POST, path, json, bearer, null);
    }

    private Resp adminPost(String path, String json, String bearer) {
        return send(HttpMethod.POST, path, json, bearer, null);
    }

    private Resp adminPost(String path, String json, String bearer, UUID idem) {
        return send(HttpMethod.POST, path, json, bearer, idem == null ? null : idem.toString());
    }

    private Resp send(HttpMethod method, String path, String json, String bearer, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method.name(),
                        json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
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

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
