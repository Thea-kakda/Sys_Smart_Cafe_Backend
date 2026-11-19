package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.ArrayList;
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
 * Slice 4A Round 2 — the product customization-group management routes over the
 * real stack (RANDOM_PORT, production JWT + RBAC, V049 schema, no migration
 * added here).
 *
 * <p>Where {@link ProductCustomizationGroupIT} pins the service contract, this
 * suite pins the parts that only exist once HTTP is in the way: 401 vs 403, the
 * bean-validation body for a missing lock token, the error envelope's request id
 * with no SQL or stack detail, and the fact that a refused write really changes
 * nothing. It also proves the capabilities block reaches the client on every read,
 * because the Dashboard decides what to render from that block alone — from V049
 * on every per-product override is a real stored write, and the lock token is the
 * attachment's own RELATIONSHIP version.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCustomizationGroupHttpIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String CUSTOMER = "00000000-0000-0000-0000-000000000007";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtTokenService tokens;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private UUID product;
    private UUID group;
    private UUID optionA;
    private UUID optionB;
    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        UUID category = newCategory();
        product = newProduct(category);
        group = newGroup();
        optionA = newOption("A");
        optionB = newOption("B");
        adminToken = staffToken(UUID.fromString(SUPER_ADMIN));
        customerToken = staffToken(UUID.fromString(CUSTOMER));
    }

    // ------------------------------------------------------------------- happy path

    @Test
    void putAttachesAndGetReturnsTheEffectiveConfiguration() {
        Resp put = send("PUT", path(group), attach(true, 4, version()), adminToken);
        assertEquals(200, put.status(), put.body());
        assertEquals(1, put.data().path("productVersion").asLong(), "the authoritative version comes back");
        assertEquals(1, attachmentRows(), "the attach really wrote the relationship");

        Resp get = send("GET", collection(), null, adminToken);
        assertEquals(200, get.status(), get.body());
        JsonNode data = get.data();
        assertEquals(product.toString(), data.path("productId").asText());
        assertEquals(1, data.path("productVersion").asLong());
        JsonNode view = data.path("groups").get(0);
        assertEquals(group.toString(), view.path("groupId").asText());
        assertEquals("SINGLE", view.path("selectionType").asText());
        // Per-product writable columns round-trip.
        assertTrue(view.path("required").asBoolean());
        assertEquals(4, view.path("displayOrder").asInt());
        // Inherited master limits (no override sent), reported as the effective values.
        assertEquals(1, view.path("minimumSelections").asInt());
        assertEquals(1, view.path("maximumSelections").asInt());
        // No default was sent, so none is invented.
        assertTrue(view.path("defaultOptionId").isNull(), "no default until one is set");
        assertTrue(view.path("relationshipId").isNull(), "the identity is the (product, group) pair");
        assertEquals(2, view.path("allowedOptions").size(), "every active option of the master group");
        // V049: the relationship carries its OWN token, a brand-new attachment starts at 0
        // even though the product aggregate already advanced to 1.
        assertEquals(0L, view.path("relationshipVersion").asLong(),
                "relationshipVersion is the independent attachment version from V049");
        assertEquals(1, data.path("productVersion").asLong());
    }


    @Test
    void capabilitiesBlockReachesTheClientOnEveryRead() {
        JsonNode caps = send("GET", collection(), null, adminToken).data().path("capabilities");
        // Round 2 (V049): every per-product override is a real stored column or table.
        assertTrue(caps.path("supportsRequiredOverride").asBoolean());
        assertTrue(caps.path("supportsDisplayOrderOverride").asBoolean());
        assertTrue(caps.path("supportsMinimumSelectionsOverride").asBoolean());
        assertTrue(caps.path("supportsMaximumSelectionsOverride").asBoolean());
        assertTrue(caps.path("supportsDefaultOptionOverride").asBoolean());
        assertTrue(caps.path("supportsAllowedOptionSubset").asBoolean());
        assertEquals("RELATIONSHIP", caps.path("relationshipVersionSource").asText());
    }

    @Test
    void secondPutOnTheSamePairUpdatesInsteadOfDuplicating() {
        send("PUT", path(group), attach(true, 1, version()), adminToken);
        Resp second = send("PUT", path(group), attach(false, 8, version()), adminToken);

        assertEquals(200, second.status(), second.body());
        assertEquals(2, second.data().path("productVersion").asLong(), "the update advances the token again");
        assertEquals(1, attachmentRows(), "PUT is an upsert, never a second row");
        JsonNode view = send("GET", collection(), null, adminToken).data().path("groups").get(0);
        assertFalse(view.path("required").asBoolean());
        assertEquals(8, view.path("displayOrder").asInt());
    }

    @Test
    void deleteDetachesOnlyTheRelationshipAndTheFollowingGetIsEmpty() {
        send("PUT", path(group), attach(true, 1, version()), adminToken);
        long current = version();

        Resp del = send("DELETE", path(group) + "?expectedProductVersion=" + current, null, adminToken);
        assertEquals(200, del.status(), del.body());
        assertEquals(current + 1, del.data().path("productVersion").asLong());
        assertEquals(0, attachmentRows(), "the relationship row is gone");
        assertEquals(0, count("select count(*) from catalog.product_customization_options where product_id=?",
                product), "the composite FK cascade removed the option allow-list rows");
        assertEquals(List.of(), groupsOf(send("GET", collection(), null, adminToken)), "read reflects the detach");

        // The masters survive their product letting go of them.
        assertEquals(1, count("select count(*) from catalog.customization_groups where group_id=?", group));
        assertEquals(2, count("select count(*) from catalog.customization_options where group_id=?", group));
    }

    // ------------------------------------------------------------------ error codes

    @Test
    void staleExpectedProductVersionIsConflictWithTheStableCode() {
        send("PUT", path(group), attach(true, 1, version()), adminToken);

        Resp stale = send("PUT", path(group), attach(false, 2, 99L), adminToken);
        assertEquals(409, stale.status(), stale.body());
        assertEquals("PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT", errorCode(stale));
        assertEquals(1, attachmentRows(), "the rejected write did not add or change a row");
        assertEquals(1, jdbc.queryForObject("select display_order from catalog.product_customization_groups "
                + "where product_id=? and group_id=?", Integer.class, product, group).intValue(),
                "the rejected write kept the stored display order");
    }

    @Test
    void deleteWithStaleVersionIsConflictAndKeepsTheAttachment() {
        send("PUT", path(group), attach(true, 1, version()), adminToken);

        Resp stale = send("DELETE", path(group) + "?expectedProductVersion=99", null, adminToken);
        assertEquals(409, stale.status(), stale.body());
        assertEquals("PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT", errorCode(stale));
        assertEquals(1, attachmentRows());
    }

    @Test
    void echoedRelationshipVersionMustAgreeWithTheAttachmentToken() {
        // A brand-new attachment's own token is 0; echoing anything else is a stale write.
        String body = "{\"required\":true,\"displayOrder\":1,\"expectedProductVersion\":" + version()
                + ",\"relationshipVersion\":7}";
        Resp r = send("PUT", path(group), body, adminToken);
        assertEquals(409, r.status(), r.body());
        assertEquals("PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT", errorCode(r));
        assertEquals(0, attachmentRows(), "the rejected attach wrote nothing");
    }

    @Test
    void perProductSelectionLimitsAreStoredAndInvalidValuesAreAStable400() {
        // The master value echoed back is now a real stored override, not a refusal.
        Resp echo = send("PUT", path(group),
                "{\"required\":true,\"displayOrder\":1,\"minimumSelections\":1,\"maximumSelections\":1,"
                        + "\"expectedProductVersion\":" + version() + "}", adminToken);
        assertEquals(200, echo.status(), echo.body());
        JsonNode view = echo.data().path("groups").get(0);
        assertEquals(1, view.path("minimumSelections").asInt());
        assertEquals(1, view.path("maximumSelections").asInt());
        Integer storedMin = jdbc.queryForObject("select minimum_selections from catalog.product_customization_groups "
                + "where product_id=? and group_id=?", Integer.class, product, group);
        assertEquals(1, storedMin.intValue(), "the override column really holds the value");

        // SINGLE capped at 1: a minimum above the maximum violates max >= min.
        Resp min = send("PUT", path(group),
                "{\"required\":true,\"displayOrder\":1,\"minimumSelections\":2,"
                        + "\"expectedProductVersion\":" + version() + "}", adminToken);
        assertEquals(400, min.status(), min.body());
        assertEquals("INVALID_SELECTION_LIMITS", errorCode(min));

        Resp max = send("PUT", path(group),
                "{\"required\":true,\"displayOrder\":1,\"minimumSelections\":1,\"maximumSelections\":3,"
                        + "\"expectedProductVersion\":" + version() + "}", adminToken);
        assertEquals(400, max.status(), max.body());
        assertEquals("INVALID_SELECTION_LIMITS", errorCode(max));

        // The rejected writes left the stored override untouched.
        Integer afterMin = jdbc.queryForObject("select minimum_selections from catalog.product_customization_groups "
                + "where product_id=? and group_id=?", Integer.class, product, group);
        assertEquals(1, afterMin.intValue(), "a refused write must not change the stored limits");
    }

    @Test
    void perProductDefaultOptionRoundTripsWhenItIsAnAllowedOption() {
        Resp r = send("PUT", path(group),
                "{\"required\":true,\"displayOrder\":1,\"defaultOptionId\":\"" + optionA + "\","
                        + "\"expectedProductVersion\":" + version() + "}", adminToken);
        assertEquals(200, r.status(), r.body());
        JsonNode view = r.data().path("groups").get(0);
        assertEquals(optionA.toString(), view.path("defaultOptionId").asText(), "the default round-trips");
        assertEquals(1, attachmentRows(), "one relationship row carries the default column");

        // A default outside the effective allowed set is refused with its stable code,
        // and the refusal writes nothing at all.
        Resp bad = send("PUT", path(group),
                "{\"required\":true,\"displayOrder\":1,\"allowedOptionIds\":[\"" + optionB + "\"],"
                        + "\"defaultOptionId\":\"" + optionA + "\",\"expectedProductVersion\":" + version() + "}",
                adminToken);
        assertEquals(400, bad.status(), bad.body());
        assertEquals("DEFAULT_OPTION_NOT_ALLOWED", errorCode(bad));
        Resp after = send("GET", collection(), null, adminToken);
        assertEquals(optionA.toString(),
                after.data().path("groups").get(0).path("defaultOptionId").asText(),
                "the refused write kept the stored default untouched");
    }

    @Test
    void allowedOptionSubsetIsStoredAndUnknownOptionsAreAStable400() {
        // Attaching with an explicit subset stores exactly that allow-list.
        Resp subset = send("PUT", path(group),
                "{\"required\":true,\"displayOrder\":1,\"allowedOptionIds\":[\"" + optionA + "\"],"
                        + "\"expectedProductVersion\":" + version() + "}", adminToken);
        assertEquals(200, subset.status(), subset.body());
        JsonNode view = subset.data().path("groups").get(0);
        assertEquals(List.of(optionA.toString()), idsOf(view.path("allowedOptions")),
                "the effective allow-list is exactly the stored subset");
        assertEquals(1, count("select count(*) from catalog.product_customization_options "
                + "where product_id=? and allowed=true", product),
                "one allowed option row really was written");

        // Narrowing again flags the dropped option instead of deleting history.
        Resp swapped = send("PUT", path(group),
                "{\"required\":true,\"displayOrder\":1,\"allowedOptionIds\":[\"" + optionB + "\"],"
                        + "\"expectedProductVersion\":" + version() + "}", adminToken);
        assertEquals(200, swapped.status(), swapped.body());
        assertEquals(List.of(optionB.toString()),
                idsOf(swapped.data().path("groups").get(0).path("allowedOptions")));
        Boolean aStillTracked = jdbc.queryForObject("select allowed from catalog.product_customization_options "
                + "where product_id=? and group_id=? and option_id=?", Boolean.class, product, group, optionA);
        assertFalse(aStillTracked, "the dropped option keeps its row, flagged not-allowed");

        Resp unknown = send("PUT", path(group),
                "{\"required\":true,\"displayOrder\":2,\"allowedOptionIds\":[\"" + UUID.randomUUID() + "\"],"
                        + "\"expectedProductVersion\":" + version() + "}", adminToken);
        assertEquals(400, unknown.status(), unknown.body());
        assertEquals("OPTION_NOT_FOUND", errorCode(unknown));
        assertEquals(1, attachmentRows(), "the rejected write added no attachment");
        assertEquals(List.of(optionB.toString()),
                idsOf(send("GET", collection(), null, adminToken).data().path("groups").get(0)
                        .path("allowedOptions")),
                "the rejected write left the stored subset untouched");
    }

    @Test
    void inactiveGroupIsRejectedAndUnknownProductOrGroupAreNotFound() {
        jdbc.update("update catalog.customization_groups set active=false where group_id=?", group);
        Resp inactive = send("PUT", path(group), attach(true, 1, version()), adminToken);
        assertEquals(400, inactive.status(), inactive.body());
        assertEquals("CUSTOMIZATION_GROUP_INACTIVE", errorCode(inactive));
        jdbc.update("update catalog.customization_groups set active=true where group_id=?", group);

        Resp unknownGroup = send("PUT", path(UUID.randomUUID()), attach(true, 1, version()), adminToken);
        assertEquals(404, unknownGroup.status(), unknownGroup.body());
        assertEquals("CUSTOMIZATION_GROUP_NOT_FOUND", errorCode(unknownGroup));

        Resp unknownProduct = send("GET", "/api/v1/management/catalog/products/" + UUID.randomUUID()
                + "/customization-groups", null, adminToken);
        assertEquals(404, unknownProduct.status(), unknownProduct.body());
        assertEquals("PRODUCT_NOT_FOUND", errorCode(unknownProduct));

        Resp missingAttachment = send("DELETE", path(group) + "?expectedProductVersion=" + version(), null,
                adminToken);
        assertEquals(404, missingAttachment.status(), missingAttachment.body());
        assertEquals("PRODUCT_CUSTOMIZATION_GROUP_NOT_FOUND", errorCode(missingAttachment));
    }

    @Test
    void aSingleGroupCappingAboveOneIsRefusedRatherThanHalfHonoured() {
        // SINGLE min 1 / max 2 is legal on the master row but cannot be honoured as
        // a single choice; the write path refuses it instead of guessing.
        jdbc.update("update catalog.customization_groups set maximum_selections=2 where group_id=?", group);
        Resp r = send("PUT", path(group), attach(true, 1, version()), adminToken);
        assertEquals(400, r.status(), r.body());
        assertEquals("INVALID_SELECTION_LIMITS", errorCode(r));
        assertEquals(0, attachmentRows());
    }

    // ------------------------------------------------------------------ security

    @Test
    void unauthenticatedIs401AndACallerWithoutProductManageIs403OnEveryVerb() {
        assertEquals(401, send("GET", collection(), null, null).status());
        assertEquals(401, send("PUT", path(group), attach(true, 1, 0L), null).status());
        assertEquals(401, send("DELETE", path(group) + "?expectedProductVersion=0", null, null).status());

        Resp read = send("GET", collection(), null, customerToken);
        assertEquals(403, read.status(), read.body());
        Resp write = send("PUT", path(group), attach(true, 1, version()), customerToken);
        assertEquals(403, write.status(), write.body());
        Resp del = send("DELETE", path(group) + "?expectedProductVersion=" + version(), null, customerToken);
        assertEquals(403, del.status(), del.body());
        assertEquals(0, attachmentRows(), "a refused caller must not have written anything");
    }

    @Test
    void missingLockTokenIsAValidation400NamingTheField() {
        Resp r = send("PUT", path(group), "{\"required\":true,\"displayOrder\":1}", adminToken);
        assertEquals(400, r.status(), r.body());
        assertEquals("VALIDATION_ERROR", errorCode(r));
        assertTrue(r.tree().path("error").path("details").toString().contains("expectedProductVersion"),
                r.body());
        assertEquals(0, attachmentRows());
    }

    @Test
    void errorEnvelopeCarriesARequestIdAndLeaksNoSqlOrStackDetail() {
        Resp r = send("PUT", path(group),
                "{\"required\":true,\"displayOrder\":-1,\"expectedProductVersion\":" + version() + "}", adminToken);
        assertEquals(400, r.status(), r.body());
        assertNotNull(r.tree().path("requestId").asText());
        assertFalse(r.tree().path("requestId").asText().isBlank(), "clients quote this id when reporting");
        String body = r.body().toLowerCase();
        for (String leak : List.of("insert into", "product_customization_groups", "exception", "stack",
                "trace", "constraint", "org.hibernate", "java.lang")) {
            assertFalse(body.contains(leak), "response leaked '" + leak + "': " + r.body());
        }
    }

    @Test
    void writesAreAuditedAndTouchNoCartOrderInventoryOrPriceTable() {
        long carts = count("select count(*) from cart.carts", null);
        long orders = count("select count(*) from ordering.orders", null);
        long balances = count("select count(*) from inventory.inventory_balances", null);
        long ledger = count("select count(*) from inventory.stock_transactions", null);
        long products = count("select count(*) from catalog.products", null);

        send("PUT", path(group), attach(true, 1, version()), adminToken);
        send("PUT", path(group), attach(false, 2, version()), adminToken);
        send("DELETE", path(group) + "?expectedProductVersion=" + version(), null, adminToken);

        assertEquals(carts, count("select count(*) from cart.carts", null));
        assertEquals(orders, count("select count(*) from ordering.orders", null));
        assertEquals(balances, count("select count(*) from inventory.inventory_balances", null));
        assertEquals(ledger, count("select count(*) from inventory.stock_transactions", null));
        assertEquals(products, count("select count(*) from catalog.products", null), "no product was created");

        assertEquals(1, count("select count(*) from audit.audit_logs where action='CATALOG_PRODUCT_GROUP_ATTACH' "
                + "and aggregate_id=?", product));
        assertEquals(1, count("select count(*) from audit.audit_logs where action='CATALOG_PRODUCT_GROUP_UPDATE' "
                + "and aggregate_id=?", product));
        assertEquals(1, count("select count(*) from audit.audit_logs where action='CATALOG_PRODUCT_GROUP_DETACH' "
                + "and aggregate_id=?", product));
    }

    // ------------------------------------------------------------------ helpers

    private String collection() {
        return "/api/v1/management/catalog/products/" + product + "/customization-groups";
    }

    private String path(UUID groupId) {
        return collection() + "/" + groupId;
    }

    private String attach(boolean required, int displayOrder, long expectedProductVersion) {
        return "{\"required\":" + required + ",\"displayOrder\":" + displayOrder + ",\"expectedProductVersion\":"
                + expectedProductVersion + "}";
    }

    private long version() {
        Long v = jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class, product);
        return v == null ? -1L : v;
    }

    private int attachmentRows() {
        return count("select count(*) from catalog.product_customization_groups where product_id=?", product);
    }

    private List<String> groupsOf(Resp resp) {
        List<String> ids = new ArrayList<>();
        resp.data().path("groups").forEach(n -> ids.add(n.path("groupId").asText()));
        return ids;
    }

    private List<String> idsOf(JsonNode options) {
        List<String> ids = new ArrayList<>();
        options.forEach(n -> ids.add(n.path("optionId").asText()));
        return ids;
    }

    private int count(String sql, UUID arg) {
        Integer n = arg == null ? jdbc.queryForObject(sql, Integer.class)
                : jdbc.queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    private static String errorCode(Resp r) {
        return r.tree().path("error").path("code").asText();
    }

    private Resp send(String method, String path, String body, String bearer) {
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

    // ------------------------------------------------------------------ fixtures

    private UUID newCategory() {
        UUID id = UUID.randomUUID();
        String code = "CGHC-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, "
                + "display_order, active, created_at, updated_at, version) values (?,?,?,10,true,now(),now(),0)",
                id, code, "Cat " + code);
        return id;
    }

    private UUID newProduct(UUID categoryId) {
        UUID id = UUID.randomUUID();
        String code = "CGHP-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, "
                + "version) values (?,?,?,?,?,'USD',3,0.10,true,true,now(),now(),0)", id, code, categoryId,
                "CGH Product", new BigDecimal("4.00"));
        return id;
    }

    private UUID newGroup() {
        UUID id = UUID.randomUUID();
        String code = "CGHG-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.customization_groups (group_id, group_code, group_name, selection_type, "
                + "minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,'SINGLE',1,1,10,true,now(),now(),0)", id, code, "Group " + code);
        return id;
    }

    private UUID newOption(String suffix) {
        UUID id = UUID.randomUUID();
        String code = "CGHO-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.customization_options (option_id, group_id, option_code, option_name, "
                + "additional_price, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,0.0000,?,true,now(),now(),0)", id, group, code, "Option " + code + " " + suffix,
                "A".equals(suffix) ? 10 : 20);
        return id;
    }

    private String staffToken(UUID roleId) {
        UUID userId = UUID.randomUUID();
        String email = "pcg-http-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),"
                + "now(),0)", userId, "PCG HTTP Actor", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,null,?)", UUID.randomUUID(), userId, roleId,
                java.sql.Timestamp.from(Instant.now()));
        return tokens.access(userId, 0L, Set.of());
    }
}
