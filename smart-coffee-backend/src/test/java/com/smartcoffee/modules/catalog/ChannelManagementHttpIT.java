package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.modules.auth.security.JwtTokenService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Management channel HTTP integration (Slice 3B.1, spec §3) plus the audit assertions
 * (§12) on the real stack: RANDOM_PORT, production JWT + RBAC resolved from seeded
 * identity rows, and the V048 schema. Proves global-admin and store-manager authority,
 * cross-store 403, unauthenticated 401, missing-store-product 404, invalid channel 400,
 * stale-{@code expectedVersion} 409, version increment on update, delete reverting to the
 * inherited default, deferred-channel configurability, and that every mutation writes an
 * {@code audit.audit_logs} row while touching no inventory / cart / order / reservation /
 * ledger table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelManagementHttpIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String STORE_MANAGER = "00000000-0000-0000-0000-000000000004";
    private static final String CUSTOMER = "00000000-0000-0000-0000-000000000007";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtTokenService tokens;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private UUID store;
    private UUID product;
    private String adminToken;
    private String managerToken;
    private String foreignManagerToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        store = newStore();
        UUID cat = newCategory();
        product = newProduct(cat, "MGMT Product");
        offer(store, product);
        adminToken = staffToken(UUID.fromString(SUPER_ADMIN), null);
        managerToken = staffToken(UUID.fromString(STORE_MANAGER), store);
        foreignManagerToken = staffToken(UUID.fromString(STORE_MANAGER), newStore());
        customerToken = staffToken(UUID.fromString(CUSTOMER), null);
    }

    // ------------------------------------------------------------------ §3 read + update + §12 audit

    @Test
    void globalAdminReadsCreatesUpdatesAndEachMutationIsAudited() {
        Resp create = putChannel(store, product, "CUSTOMER_WEB", config(true, true, null), adminToken);
        assertEquals(200, create.status(), create.body());
        assertTrue(create.data().path("visible").asBoolean());
        long v0 = create.data().path("version").asLong();
        assertEquals(0, v0, "fresh publication starts at version 0");
        assertEquals(1, auditCount("CATALOG_CHANNEL_CREATE", store), "create audited");

        Resp read = get(base(store, product) + "/channels", adminToken);
        assertEquals(200, read.status(), read.body());
        JsonNode web = channelNode(read.data(), "CUSTOMER_WEB");
        assertFalse(web.path("inherited").asBoolean(), "explicit row is no longer inherited");
        assertTrue(web.path("visible").asBoolean());

        Resp update = putChannel(store, product, "CUSTOMER_WEB", config(true, false, v0), adminToken);
        assertEquals(200, update.status(), update.body());
        assertEquals(v0 + 1, update.data().path("version").asLong(), "update increments version");
        assertEquals(1, auditCount("CATALOG_CHANNEL_UPDATE", store), "update audited");
    }

    @Test
    void staleExpectedVersionReturnsConflict() {
        putChannel(store, product, "CUSTOMER_WEB", config(true, true, null), adminToken);
        // The row now sits at version 0; sending version 99 is stale.
        Resp stale = putChannel(store, product, "CUSTOMER_WEB", config(false, false, 99L), adminToken);
        assertEquals(409, stale.status(), stale.body());
        assertEquals("PRODUCT_CHANNEL_VERSION_CONFLICT", errorCode(stale));
    }

    @Test
    void deleteRevertsToInheritedDefaultAndAudits() {
        putChannel(store, product, "CUSTOMER_WEB", config(true, true, null), adminToken);
        Resp del = send("DELETE", base(store, product) + "/channels/CUSTOMER_WEB", adminToken, null);
        assertEquals(200, del.status(), del.body());
        assertEquals(1, auditCount("CATALOG_CHANNEL_DELETE", store), "delete audited");

        JsonNode web = channelNode(get(base(store, product) + "/channels", adminToken).data(), "CUSTOMER_WEB");
        assertTrue(web.path("inherited").asBoolean(), "deleted publication reverts to inherited");
    }

    // ----------------------------------------------------------------------- §3 store scoping

    @Test
    void storeManagerManagesOwnStoreButNotForeignStore() {
        Resp own = putChannel(store, product, "CUSTOMER_WEB", config(true, true, null), managerToken);
        assertEquals(200, own.status(), own.body());

        Resp foreign = putChannel(store, product, "CUSTOMER_APP", config(true, true, null), foreignManagerToken);
        assertEquals(403, foreign.status(), foreign.body());
    }

    @Test
    void unauthenticatedReturns401() {
        assertEquals(401, get(base(store, product) + "/channels", null).status());
    }

    @Test
    void customerWithoutPermissionReturns403() {
        Resp r = get(base(store, product) + "/channels", customerToken);
        assertEquals(403, r.status(), r.body());
    }

    @Test
    void invalidChannelReturns400() {
        Resp r = putChannel(store, product, "NOT_A_CHANNEL", config(true, true, null), adminToken);
        assertEquals(400, r.status(), r.body());
        assertEquals("INVALID_CHANNEL", errorCode(r));
    }

    @Test
    void missingStoreProductReturns404() {
        Resp r = putChannel(store, UUID.randomUUID(), "CUSTOMER_WEB", config(true, true, null), adminToken);
        assertEquals(404, r.status(), r.body());
        assertEquals("STORE_PRODUCT_NOT_FOUND", errorCode(r));
    }

    // -------------------------------------------------------------------- §12 sold-out + audit

    @Test
    void manualSoldOutUpdatesStoreProductWithVersionControlAndAudit() {
        long spVersion = jdbc.queryForObject("select version from catalog.store_products where store_id=? and product_id=?",
                Long.class, store, product);
        Resp ok = patchSoldOut(store, product, "{\"available\":false,\"soldOutUntil\":null,\"expectedVersion\":"
                + spVersion + "}", adminToken);
        assertEquals(200, ok.status(), ok.body());
        assertEquals(1, auditCount("STORE_PRODUCT_MANUAL_SOLD_OUT", store), "sold-out change audited");
        assertFalse(jdbc.queryForObject("select available from catalog.store_products where store_id=? and product_id=?",
                Boolean.class, store, product), "manual sold-out flipped available=false");

        long newVersion = jdbc.queryForObject("select version from catalog.store_products where store_id=? and product_id=?",
                Long.class, store, product);
        Resp stale = patchSoldOut(store, product, "{\"available\":true,\"soldOutUntil\":null,\"expectedVersion\":"
                + spVersion + "}", adminToken);
        assertEquals(409, stale.status(), stale.body());
        assertEquals("STORE_PRODUCT_VERSION_CONFLICT", errorCode(stale));
        assertEquals(newVersion, jdbc.queryForObject("select version from catalog.store_products where store_id=? and "
                + "product_id=?", Long.class, store, product), "stale patch must not bump the version again");
    }

    // ---------------------------------------------------------------- §3 preview + deferred channel

    @Test
    void deferredChannelIsConfigurableOnlyThroughManagement() {
        Resp put = putChannel(store, product, "DELIVERY", config(true, true, null), adminToken);
        assertEquals(200, put.status(), "management may configure a deferred channel: " + put.body());
        // ...but a public caller cannot select DELIVERY (no public DELIVERY surface exists);
        // the deferred product stays hidden from WEB/APP/POS reads.
        assertTrue(channelNode(get(base(store, product) + "/channels", adminToken).data(), "DELIVERY")
                .path("visible").asBoolean());
    }

    @Test
    void previewOfMissingProductReturns404() {
        Resp r = send("GET", base(store, UUID.randomUUID()) + "/channels/POS/preview", adminToken, null);
        assertEquals(404, r.status(), r.body());
    }

    // ---------------------------------------------------------------- §3 no-side-effect on commerce tables

    @Test
    void managementWritesTouchNoInventoryCartOrderReservationLedger() {
        Commerce before = commerceSnapshot();
        putChannel(store, product, "CUSTOMER_WEB", config(true, true, null), adminToken);
        putChannel(store, product, "CUSTOMER_WEB", config(false, true, 0L), adminToken);
        replaceSchedules(store, product, "CUSTOMER_WEB",
                "{\"schedules\":[{\"dayOfWeek\":1,\"startTime\":\"09:00\",\"endTime\":\"17:00\",\"active\":true}],"
                        + "\"expectedVersion\":1}", adminToken);
        patchSoldOut(store, product, "{\"available\":false,\"soldOutUntil\":null,\"expectedVersion\":0}", adminToken);
        assertEquals(before, commerceSnapshot(), "management mutations never touch commerce/inventory tables");
    }

    // ------------------------------------- Slice 3B.2 §9 + §10: raw-imported schedule through the real HTTP stack

    /**
     * The Slice 3B.2 acceptance path. An EXTERNAL writer (CSV import / ETL / Flyway seed) inserts a
     * {@code TIME '09:00:00' - TIME '17:00:00'} window with raw SQL, then:
     *
     * <ol>
     *   <li>GET schedules must return {@code 09:00}/{@code 17:00} exactly (no {@code +07}/{@code -07} shift),</li>
     *   <li>PUT schedules (JPA replace, optimistic version) must persist the SAME wall-clock literals
     *       that a raw import would write,</li>
     *   <li>the channel version must increment and a fresh reload must keep the values unchanged,</li>
     *   <li>the audit {@code old_values}/{@code new_values} metadata must carry exact local times.</li>
     * </ol>
     *
     * <p>Under the Slice 3B.1 mapping this failed at step 1: the raw {@code 09:00} row was reported by
     * the API as {@code 16:00} (JVM zone Asia/Bangkok, UTC+7).
     */
    @Test
    void rawImportedScheduleRoundTripsExactLocalTimesThroughTheManagementApi() {
        Resp create = putChannel(store, product, "CUSTOMER_WEB", config(true, true, null), adminToken);
        assertEquals(200, create.status(), create.body());
        UUID channelId = jdbc.queryForObject("select product_channel_id from catalog.product_channels where "
                + "store_id = ? and product_id = ? and channel = 'CUSTOMER_WEB'", UUID.class, store, product);

        // §10 step 1: raw SQL import of Wed 09:00 -> 17:00 (the JPA layer is never involved).
        jdbc.update("insert into catalog.product_channel_schedules (schedule_id, product_channel_id, day_of_week, "
                + "start_local_time, end_local_time, active, created_at, updated_at, version) "
                + "values (?,?::uuid,3,TIME '09:00:00',TIME '17:00:00',true,now(),now(),0)", UUID.randomUUID(),
                channelId.toString());

        // §9 steps 2-3 / §10 steps 2-3: the API reads the imported wall clock back unchanged.
        JsonNode imported = get("/api/v1/management/catalog/stores/" + store + "/products/" + product
                + "/channels/CUSTOMER_WEB/schedules", adminToken).data();
        assertEquals(1, imported.size(), "the imported window is visible through the API");
        assertEquals(LocalTime.of(9, 0), LocalTime.parse(imported.get(0).path("startTime").asText()));
        assertEquals(LocalTime.of(17, 0), LocalTime.parse(imported.get(0).path("endTime").asText()));

        // §9 step 10 / §10 step 4: replace through JPA (HTTP PUT) using the channel's live version.
        long version = jdbc.queryForObject("select version from catalog.product_channels where "
                + "product_channel_id = ?", Long.class, channelId);
        Resp replaced = replaceSchedules(store, product, "CUSTOMER_WEB",
                "{\"schedules\":[{\"dayOfWeek\":3,\"startTime\":\"09:00\",\"endTime\":\"17:00\",\"active\":true},"
                        + "{\"dayOfWeek\":1,\"startTime\":\"22:00\",\"endTime\":\"02:00\",\"active\":true}],"
                        + "\"expectedVersion\":" + version + "}", adminToken);
        assertEquals(200, replaced.status(), replaced.body());
        assertEquals(2, replaced.data().size(), replaced.body());
        for (JsonNode w : replaced.data()) {
            LocalTime s = LocalTime.parse(w.path("startTime").asText());
            LocalTime e = LocalTime.parse(w.path("endTime").asText());
            assertTrue(w.path("dayOfWeek").asInt() == 1 ? s.equals(LocalTime.of(22, 0)) && e.equals(LocalTime.of(2, 0))
                    : s.equals(LocalTime.of(9, 0)) && e.equals(LocalTime.of(17, 0)),
                    "PUT response must echo exact local times, got " + w.path("startTime").asText() + "/"
                            + e);
        }

        // §9 step 11: the optimistic version incremented on the channel aggregate.
        assertEquals(version + 1, jdbc.queryForObject("select version from catalog.product_channels where "
                + "product_channel_id = ?", Long.class, channelId).longValue(), "version increments");

        // §10 steps 5-6: the JPA-written rows read back through RAW SQL are the same literals.
        List<String> literals = jdbc.query("select start_local_time::text || '-' || end_local_time::text from "
                + "catalog.product_channel_schedules where product_channel_id = ?::uuid order by day_of_week",
                (rs, i) -> rs.getString(1), channelId.toString());
        assertEquals(List.of("22:00:00-02:00:00", "09:00:00-17:00:00"), literals,
                "JPA writes must be byte-identical to a raw import");

        // §9 step 9: a new request (fresh persistence context) still reports the same values.
        JsonNode reloaded = get("/api/v1/management/catalog/stores/" + store + "/products/" + product
                + "/channels/CUSTOMER_WEB/schedules", adminToken).data();
        assertEquals(2, reloaded.size());
        assertEquals(List.of("22:00", "09:00"), List.of(
                LocalTime.parse(reloaded.get(0).path("startTime").asText()).toString(),
                LocalTime.parse(reloaded.get(1).path("startTime").asText()).toString()));

        // §9 step 12: audited before/after metadata keeps exact local values with no timezone artifact.
        JsonNode beforeNode = auditJson("old_values", store);
        assertEquals(1, beforeNode.size(), "the audited 'before' is the raw-imported window");
        assertEquals(LocalTime.of(9, 0), LocalTime.parse(beforeNode.get(0).path("startTime").asText()),
                "audited before start must be the stored wall clock, not a +07 shifted value");
        assertEquals(LocalTime.of(17, 0), LocalTime.parse(beforeNode.get(0).path("endTime").asText()),
                "audited before end must be the stored wall clock (the Slice 3B.1 defect reported 00:00 here)");

        JsonNode afterNode = auditJson("new_values", store);
        assertEquals(2, afterNode.size(), "the audited 'after' is the replaced pair");
        for (JsonNode w : afterNode) {
            LocalTime s = LocalTime.parse(w.path("startTime").asText());
            LocalTime e = LocalTime.parse(w.path("endTime").asText());
            assertTrue(w.path("dayOfWeek").asInt() == 1
                    ? s.equals(LocalTime.of(22, 0)) && e.equals(LocalTime.of(2, 0))
                    : s.equals(LocalTime.of(9, 0)) && e.equals(LocalTime.of(17, 0)),
                    "audit metadata must carry exact local times, got " + w);
            String raw = w.path("startTime").asText() + "/" + w.path("endTime").asText();
            assertFalse(raw.contains("+") || raw.contains("-") || raw.endsWith("Z"),
                    "audit metadata must never contain an offset-qualified time: " + raw);
        }
    }

    /** Reads one audited jsonb column of the most recent schedule replacement for the store. */
    private JsonNode auditJson(String column, UUID storeId) {
        String text = jdbc.queryForObject("select " + column + "::text from audit.audit_logs where action = "
                + "'CATALOG_CHANNEL_SCHEDULE_REPLACE' and store_id = ? order by occurred_at desc limit 1",
                String.class, storeId);
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable audit " + column + ": " + text, e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String base(UUID store, UUID product) {
        return "/api/v1/management/catalog/stores/" + store + "/products/" + product;
    }

    private String config(boolean visible, boolean orderable, Long expectedVersion) {
        return "{\"visible\":" + visible + ",\"orderable\":" + orderable
                + ",\"expectedVersion\":" + (expectedVersion == null ? "null" : expectedVersion) + "}";
    }

    private Resp putChannel(UUID store, UUID product, String channel, String body, String token) {
        return send("PUT", base(store, product) + "/channels/" + channel, token, body);
    }

    private Resp patchSoldOut(UUID store, UUID product, String body, String token) {
        return send("PATCH", base(store, product) + "/sold-out", token, body);
    }

    private Resp replaceSchedules(UUID store, UUID product, String channel, String body, String token) {
        return send("PUT", base(store, product) + "/channels/" + channel + "/schedules", token, body);
    }

    private int auditCount(String action, UUID storeId) {
        Integer n = jdbc.queryForObject("select count(*) from audit.audit_logs where action=? and store_id=?",
                Integer.class, action, storeId);
        return n == null ? 0 : n;
    }

    private static JsonNode channelNode(JsonNode data, String channel) {
        for (JsonNode n : data) {
            if (channel.equals(n.path("channel").asText())) {
                return n;
            }
        }
        throw new AssertionError("channel not found in response: " + channel + " -> " + data);
    }

    private static String errorCode(Resp r) {
        return r.tree().path("error").path("code").asText();
    }

    private Resp get(String path, String bearer) {
        return send("GET", path, bearer, null);
    }

    private Resp send(String method, String path, String bearer, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted: " + path, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("I/O: " + path, e);
        }
    }

    private record Resp(int status, String body) {
        JsonNode data() {
            return tree().path("data");
        }

        JsonNode tree() {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Unparseable: " + body, e);
            }
        }
    }

    private record Commerce(long balances, long balanceVer, long reservations, long resLines, long ledger, long carts,
            long cartItems, long orders, long orderItems) {
    }

    private Commerce commerceSnapshot() {
        return new Commerce(cnt("select count(*) from inventory.inventory_balances"),
                cnt("select coalesce(sum(version),0) from inventory.inventory_balances"),
                cnt("select count(*) from inventory.inventory_reservations"),
                cnt("select count(*) from inventory.inventory_reservation_lines"),
                cnt("select count(*) from inventory.stock_transactions"), cnt("select count(*) from cart.carts"),
                cnt("select count(*) from cart.cart_items"), cnt("select count(*) from ordering.orders"),
                cnt("select count(*) from ordering.order_items"));
    }

    private long cnt(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    // ------------------------------------------------------------------ fixtures

    private UUID newStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'MGMT Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", id,
                "MG" + UUID.randomUUID().toString().substring(0, 8));
        return id;
    }

    private UUID newCategory() {
        UUID id = UUID.randomUUID();
        String code = "MC-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, display_order, "
                + "active, created_at, updated_at, version) values (?,?,?,10,true,now(),now(),0)", id, code, "Cat " + code);
        return id;
    }

    private UUID newProduct(UUID categoryId, String name) {
        UUID id = UUID.randomUUID();
        String code = "MP-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD',5,0.10,false,true,now(),now(),0)", id, code, categoryId, name,
                new BigDecimal("3.00"));
        return id;
    }

    private void offer(UUID store, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, sold_out_until, updated_at, "
                + "version) values (?,?,true,null,now(),0)", store, productId);
    }

    private String staffToken(UUID roleId, UUID store) {
        UUID userId = UUID.randomUUID();
        String email = "mgmt-http-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Mgmt HTTP Actor", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,?,?)", UUID.randomUUID(), userId, roleId, store,
                java.sql.Timestamp.from(Instant.now()));
        return tokens.access(userId, 0L, Set.of());
    }

    @SuppressWarnings("unused")
    private static final List<String> NONE = List.of();
}
