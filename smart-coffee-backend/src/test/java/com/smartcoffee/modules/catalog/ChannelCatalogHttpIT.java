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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-HTTP catalog contract tests for Slice 3B.1 (spec §2/§7/§8/§9/§10) on the
 * production stack: RANDOM_PORT, the JWT filter chain, the server-side
 * {@code ChannelContextResolver}, the {@code /api/v1/app} server-owned route and the
 * full Flyway V001..V048 schema over Testcontainers.
 *
 * <p>Fixtures are inserted directly via SQL (post-migration, so no explicit channel row
 * exists and the read inherits legacy visibility), then explicit {@code product_channels}
 * rows are published to force per-channel divergence. Membership on a catalog page is
 * decided by the channel-visibility filter alone (availability only affects the
 * {@code orderable} verdict), so these tests prove the routing/leakage guarantees without
 * needing recipe/inventory setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelCatalogHttpIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtTokenService tokens;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    // =================================================================== §2 divergence

    @Test
    void webAndAppAreDistinctTrustedSurfacesAndNeitherLeaksTheOthersProducts() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID webOnly = newProduct(cat, "DIV WebOnly");
        UUID appOnly = newProduct(cat, "DIV AppOnly");
        UUID posOnly = newProduct(cat, "DIV PosOnly");
        UUID shared = newProduct(cat, "DIV Shared"); // no explicit rows -> inherited everywhere
        offer(store, webOnly);
        offer(store, appOnly);
        offer(store, posOnly);
        offer(store, shared);
        publish(store, webOnly, "CUSTOMER_WEB", true, true);
        publish(store, webOnly, "CUSTOMER_APP", false, false);
        publish(store, appOnly, "CUSTOMER_WEB", false, false);
        publish(store, appOnly, "CUSTOMER_APP", true, true);
        publish(store, posOnly, "CUSTOMER_WEB", false, false);
        publish(store, posOnly, "CUSTOMER_APP", false, false);
        publish(store, posOnly, "POS", true, true);

        List<String> web = names(get(webCatalog(store), null));
        List<String> app = names(get(appCatalog(store), null));
        List<String> pos = names(get(webCatalog(store), adminToken()));

        // Web sees web-only + shared; app-only and pos-only must not leak.
        assertTrue(web.contains("DIV WebOnly"), "web-visible present: " + web);
        assertTrue(web.contains("DIV Shared"));
        assertFalse(web.contains("DIV AppOnly"), "APP-only leaked into WEB: " + web);
        assertFalse(web.contains("DIV PosOnly"), "POS-only leaked into WEB: " + web);

        // App sees app-only + shared; web-only and pos-only must not leak.
        assertTrue(app.contains("DIV AppOnly"), "app-visible present: " + app);
        assertTrue(app.contains("DIV Shared"));
        assertFalse(app.contains("DIV WebOnly"), "WEB-only leaked into APP: " + app);
        assertFalse(app.contains("DIV PosOnly"), "POS-only leaked into APP: " + app);

        // POS is the broadest surface (pos-only included), proving the three are distinct.
        assertTrue(pos.contains("DIV PosOnly"), "trusted staff resolves POS: " + pos);
    }

    @Test
    void publicCallersCannotImpersonateCustomerAppViaSelectorOrHeader() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID webVisible = newProduct(cat, "IMP WebVisible");
        UUID appOnly = newProduct(cat, "IMP AppOnly");
        offer(store, webVisible);
        offer(store, appOnly);
        publish(store, appOnly, "CUSTOMER_WEB", false, false);
        publish(store, appOnly, "CUSTOMER_APP", true, true);

        // The shared catalog ignores any caller-supplied channel selector or header; the
        // ONLY way to reach CUSTOMER_APP is the server-owned /api/v1/app route.
        List<String> spoofedQuery = names(get(webCatalog(store) + "?channel=CUSTOMER_APP", null));
        List<String> spoofedHeader = names(getWithHeader(webCatalog(store), "X-Channel", "CUSTOMER_APP"));

        assertFalse(spoofedQuery.contains("IMP AppOnly"), "?channel cannot elevate to APP: " + spoofedQuery);
        assertFalse(spoofedHeader.contains("IMP AppOnly"), "X-Channel cannot elevate to APP: " + spoofedHeader);
        assertTrue(spoofedQuery.contains("IMP WebVisible"));
    }

    // =================================================================== §9 APP route

    @Test
    void appRouteResolvesCustomerAppAndAgreesBetweenListAndDetail() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID product = newProduct(cat, "APP Detail");
        offer(store, product);
        publish(store, product, "CUSTOMER_WEB", false, false); // hidden on web, visible on app
        publish(store, product, "CUSTOMER_APP", true, true);

        assertTrue(names(get(appCatalog(store), null)).contains("APP Detail"), "app-only product listed via /app");

        Resp detail = get(appCatalog(store) + "/products/" + product, null);
        assertEquals(200, detail.status(), detail.body());
        assertTrue(detail.data().path("visible").asBoolean(), "detail agrees with list visibility");

        // The same product is 404 on the WEB detail (hidden there), proving the two
        // surfaces run the identical ChannelCatalogService with distinct channel context.
        assertEquals(404, get(webCatalog(store) + "/products/" + product, null).status());
    }

    // =================================================================== §8 CUSTOMER_WEB

    @Test
    void anonymousResolvesWebAndPublicResponseLeaksNoInternalFields() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID shown = newProduct(cat, "WEB Shown");
        UUID hidden = newProduct(cat, "WEB Hidden");
        offer(store, shown);
        offer(store, hidden);
        publish(store, hidden, "CUSTOMER_WEB", false, false);

        Resp list = get(webCatalog(store), null);
        assertEquals(200, list.status(), list.body());
        List<String> names = names(list);
        assertTrue(names.contains("WEB Shown"));
        assertFalse(names.contains("WEB Hidden"), "web-hidden product must be absent");

        String lower = list.body().toLowerCase();
        for (String forbidden : List.of("cost", "supplier", "recipeversion", "inventoryitemid", "reconcil", "stack",
                "select ", "com.smartcoffee", "reserved_stock")) {
            assertFalse(lower.contains(forbidden), "public response leaks internal field: " + forbidden);
        }
    }

    @Test
    void webPublicationWindowIsEnforcedOnThePublicSurface() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID product = newProduct(cat, "WEB Window");
        // Publication window in the past -> outside window -> not orderable (still visible).
        jdbc.update("insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, visible, "
                + "orderable, display_order, available_from, available_until, created_at, updated_at, version) "
                + "values (?,?,?,?,true,true,0,?,?,now(),now(),0)", UUID.randomUUID(), store, product, "CUSTOMER_WEB",
                java.sql.Timestamp.from(Instant.parse("2001-01-01T00:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2001-01-02T00:00:00Z")));
        offer(store, product);

        JsonNode row = find(get(webCatalog(store), null), product);
        assertNotNull(row, "outside-window product is still visible");
        assertFalse(row.path("orderable").asBoolean(), "expired publication window forces orderable=false");
        assertTrue(row.path("reasonCodes").toString().contains("CHANNEL_OUTSIDE_PUBLICATION_WINDOW"));
    }

    // =================================================================== §7 POS

    @Test
    void trustedPosContextListsPosProductsHidesPosHiddenAndKeepsSoldOutVisible() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID normal = newProduct(cat, "POS Normal");
        UUID hidden = newProduct(cat, "POS Hidden");
        UUID soldOut = newProduct(cat, "POS SoldOut");
        offer(store, normal);
        offer(store, hidden);
        // manual sold-out lever: available=false (still visible, not orderable)
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, sold_out_until, updated_at, "
                + "version) values (?,?,false,null,now(),0)", store, soldOut);
        publish(store, hidden, "POS", false, false);

        List<String> listed = names(get(webCatalog(store), adminToken()));
        assertTrue(listed.contains("POS Normal"), "trusted staff resolves POS surface: " + listed);
        assertFalse(listed.contains("POS Hidden"), "POS-hidden product absent: " + listed);
        assertTrue(listed.contains("POS SoldOut"), "sold-out product remains present");

        JsonNode soldOutRow = find(get(webCatalog(store), adminToken()), soldOut);
        assertNotNull(soldOutRow);
        assertFalse(soldOutRow.path("orderable").asBoolean(), "sold-out is present but not orderable");
        assertFalse(soldOutRow.path("available").asBoolean());
    }

    @Test
    void detailExposesCustomizationGroupsSoPosCannotSubmitEmptyRequiredConfig() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID product = newProduct(cat, "POS Milk Latte");
        offer(store, product);
        // Required milk group + a sugar tier group, mirroring the prior POS regression.
        UUID milk = newGroup(product, "MILKGRP", "Milk", "SINGLE", 1);
        newOption(milk, "OAT", "Oat Milk", "0.50");
        newOption(milk, "WHOLE", "Fresh Milk", "0.00");
        newGroup(product, "SUGARGRP", "Sugar", "SINGLE", 1);

        Resp detail = get(webCatalog(store) + "/products/" + product, adminToken());
        assertEquals(200, detail.status(), detail.body());
        JsonNode groups = detail.data().path("customizationGroups");
        assertTrue(groups.isArray() && groups.size() >= 2,
                "customization groups populated in detail: " + detail.body());
        boolean hasMilk = false;
        for (JsonNode g : groups) {
            if ("Milk".equals(g.path("name").asText())) {
                hasMilk = true;
                assertTrue(g.path("options").size() >= 2, "milk options present");
            }
        }
        assertTrue(hasMilk, "milk customization group present so POS cannot submit an empty required config");
    }

    // ==================================================== Slice 4B · default Option projection

    /**
     * Slice 4B (POS Variant/Option/Availability integration): the per-product default is
     * projected additively — {@code customizationGroups[].defaultOptionId} plus
     * {@code options[].isDefault} — sourced ONLY from the V049 attachment row via
     * EffectiveCustomizationResolver. POS/Customer must never infer a default (first,
     * cheapest or first-available option); a null default must project as null with zero
     * isDefault flags.
     */
    @Test
    void declaredProductDefaultIsProjectedWithExactlyOneMatchingIsDefaultFlag() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID product = newProduct(cat, "4B Default Latte");
        offer(store, product);
        UUID milk = newGroup(product, "DEFMILK", "Milk", "SINGLE", 1);
        UUID oat = newOption(milk, "OATD", "Oat Milk", "0.50");
        newOption(milk, "WHOLED", "Fresh Milk", "0.00");
        // Default the alphabetically-LAST option: position/name must not matter.
        setAttachmentDefault(product, milk, oat);

        Resp detail = get(webCatalog(store) + "/products/" + product, adminToken());
        assertEquals(200, detail.status(), detail.body());
        JsonNode group = groupByName(detail.data().path("customizationGroups"), "Milk");
        assertNotNull(group, "milk group projected");
        assertEquals(oat.toString(), group.path("defaultOptionId").asText(),
                "attachment default_option_id is projected verbatim");
        List<String> flagged = new ArrayList<>();
        for (JsonNode o : group.path("options")) {
            if (o.path("isDefault").asBoolean()) {
                flagged.add(o.path("optionId").asText());
            }
        }
        assertEquals(List.of(oat.toString()), flagged,
                "exactly one option carries isDefault=true and it is the declared one");
    }

    @Test
    void nullDefaultProjectsNullAndNoOptionIsFlaggedEvenThoughOptionsHaveOrder() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID product = newProduct(cat, "4B No Default");
        offer(store, product);
        UUID milk = newGroup(product, "NODEFMILK", "Milk", "SINGLE", 1);
        newOption(milk, "OATN", "Oat Milk", "0.50");
        newOption(milk, "WHOLEN", "Fresh Milk", "0.00");
        // deliberately no setAttachmentDefault: NULL means "no default", never a guess.

        Resp detail = get(webCatalog(store) + "/products/" + product, adminToken());
        assertEquals(200, detail.status(), detail.body());
        JsonNode group = groupByName(detail.data().path("customizationGroups"), "Milk");
        assertNotNull(group);
        assertTrue(group.path("defaultOptionId").isNull(), "null default stays null");
        for (JsonNode o : group.path("options")) {
            assertFalse(o.path("isDefault").asBoolean(),
                    "no option may be flagged default when none is declared: " + o.path("name").asText());
        }
    }

    @Test
    void productSpecificDefaultOverridesAnyMasterLevelConvention() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID product = newProduct(cat, "4B Override Default");
        offer(store, product);
        UUID milk = newGroup(product, "OVDMILK", "Milk", "SINGLE", 1);
        UUID oat = newOption(milk, "OATO", "Oat Milk", "0.50");
        UUID whole = newOption(milk, "WHOLEO", "Fresh Milk", "0.00");
        // Master convention might suggest the first/cheapest option (Fresh Milk, 0.00);
        // the product attachment wins outright.
        setAttachmentDefault(product, milk, whole);

        Resp detail = get(webCatalog(store) + "/products/" + product, adminToken());
        JsonNode group = groupByName(detail.data().path("customizationGroups"), "Milk");
        assertEquals(whole.toString(), group.path("defaultOptionId").asText(),
                "per-product default beats any master-level convention");
        for (JsonNode o : group.path("options")) {
            assertEquals(whole.toString().equals(o.path("optionId").asText()), o.path("isDefault").asBoolean(),
                    "only the product-declared option is flagged (oat=" + oat + "): " + o.path("name").asText());
        }
    }

    @Test
    void optionsOutsideTheEffectiveAllowListAreNotProjected() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID product = newProduct(cat, "4B Subset");
        offer(store, product);
        UUID milk = newGroup(product, "SUBMILK", "Milk", "SINGLE", 1);
        UUID oat = newOption(milk, "OATS", "Oat Milk", "0.50");
        UUID whole = newOption(milk, "WHOLES", "Fresh Milk", "0.00");
        UUID soy = newOption(milk, "SOYS", "Soy Milk", "0.75");
        // Explicit subset: Soy is disallowed and must vanish from the customer-facing read.
        allowOption(product, milk, oat, true);
        allowOption(product, milk, whole, true);
        allowOption(product, milk, soy, false);
        setAttachmentDefault(product, milk, whole);

        Resp detail = get(webCatalog(store) + "/products/" + product, adminToken());
        JsonNode group = groupByName(detail.data().path("customizationGroups"), "Milk");
        List<String> ids = new ArrayList<>();
        group.path("options").forEach(o -> ids.add(o.path("optionId").asText()));
        assertEquals(2, ids.size(), "only the allowed subset is projected: " + ids);
        assertTrue(ids.contains(oat.toString()) && ids.contains(whole.toString()));
        assertFalse(ids.contains(soy.toString()), "disallowed option leaked: " + ids);
        assertEquals(whole.toString(), group.path("defaultOptionId").asText());
    }

    @Test
    void posAndCustomerDetailsReturnIdenticalEffectiveDefaultSemantics() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID product = newProduct(cat, "4B Parity");
        offer(store, product);
        UUID milk = newGroup(product, "PARMILK", "Milk", "SINGLE", 1);
        UUID oat = newOption(milk, "OATP", "Oat Milk", "0.50");
        newOption(milk, "WHOLEP", "Fresh Milk", "0.00");
        setAttachmentDefault(product, milk, oat);

        JsonNode pos = get(webCatalog(store) + "/products/" + product, adminToken()).data()
                .path("customizationGroups");
        JsonNode web = get(webCatalog(store) + "/products/" + product, null).data()
                .path("customizationGroups");
        JsonNode app = get(appCatalog(store) + "/products/" + product, null).data()
                .path("customizationGroups");
        assertEquals(pos.toString(), web.toString(), "POS and Customer WEB see one effective projection");
        assertEquals(pos.toString(), app.toString(), "POS and Customer APP see one effective projection");
        JsonNode group = groupByName(web, "Milk");
        assertEquals(oat.toString(), group.path("defaultOptionId").asText(),
                "the declared default is identical on every surface");
    }

    private JsonNode groupByName(JsonNode groups, String name) {
        for (JsonNode g : groups) {
            if (name.equals(g.path("name").asText())) {
                return g;
            }
        }
        return null;
    }

    // =================================================================== §10 pagination

    @Test
    void hiddenProductsAreFilteredBeforePaginationAcrossPages() {
        UUID store = newStore();
        UUID cat = newCategory();
        for (int i = 1; i <= 5; i++) {
            String name = String.format("PAGE P%02d", i);
            UUID p = newProduct(cat, name);
            offer(store, p);
            if (i == 2 || i == 4) {
                publish(store, p, "CUSTOMER_WEB", false, false); // hide two of the five
            }
        }

        Resp page0 = get(webCatalog(store) + "?search=PAGE&page=0&size=2", null);
        JsonNode meta = page0.data().path("pagination");
        assertEquals(3, meta.path("totalElements").asInt(), "hidden products excluded from the total");
        assertEquals(2, meta.path("totalPages").asInt(), "totals reflect visible-only membership");
        assertEquals(2, page0.data().path("items").size(), "first page full (not shortened by post-filter)");

        Resp page1 = get(webCatalog(store) + "?search=PAGE&page=1&size=2", null);
        assertEquals(1, page1.data().path("items").size(), "last page carries the remaining visible product");
    }

    @Test
    void detailCannotBypassListVisibilityPolicy() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID product = newProduct(cat, "BYPASS Hidden");
        offer(store, product);
        publish(store, product, "CUSTOMER_WEB", false, false);

        assertFalse(names(get(webCatalog(store), null)).contains("BYPASS Hidden"));
        assertEquals(404, get(webCatalog(store) + "/products/" + product, null).status(),
                "a product hidden on the channel 404s on detail too");
    }

    // ------------------------------------------------------------------ http helpers

    private String webCatalog(UUID store) {
        return "/api/v1/stores/" + store + "/catalog";
    }

    private String appCatalog(UUID store) {
        return "/api/v1/app/stores/" + store + "/catalog";
    }

    private String adminToken() {
        return staffToken(UUID.fromString(SUPER_ADMIN), null);
    }

    private Resp get(String path, String bearer) {
        return send(path, bearer, null, null);
    }

    private Resp getWithHeader(String path, String header, String value) {
        return send(path, null, header, value);
    }

    private Resp send(String path, String bearer, String extraHeader, String extraValue) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (extraHeader != null) {
            b.header(extraHeader, extraValue);
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

    private final class Resp {
        private final int status;
        private final String body;

        Resp(int status, String body) {
            this.status = status;
            this.body = body;
        }

        int status() {
            return status;
        }

        String body() {
            return body;
        }

        JsonNode data() {
            return tree().path("data");
        }

        private JsonNode tree() {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Unparseable: " + body, e);
            }
        }
    }

    private List<String> names(Resp r) {
        List<String> out = new ArrayList<>();
        r.data().path("items").forEach(node -> out.add(node.path("name").asText()));
        return out;
    }

    private JsonNode find(Resp r, UUID productId) {
        for (JsonNode node : r.data().path("items")) {
            if (productId.toString().equals(node.path("productId").asText())) {
                return node;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ fixtures

    private UUID newStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'HTTP Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", id,
                "HT" + UUID.randomUUID().toString().substring(0, 8));
        return id;
    }

    private UUID newCategory() {
        UUID id = UUID.randomUUID();
        String code = "HC-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, display_order, "
                + "active, created_at, updated_at, version) values (?,?,?,10,true,now(),now(),0)", id, code,
                "Cat " + code);
        return id;
    }

    private UUID newProduct(UUID categoryId, String name) {
        UUID id = UUID.randomUUID();
        String code = "HP-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD',5,0.10,true,true,now(),now(),0)", id, code, categoryId, name,
                new BigDecimal("3.00"));
        return id;
    }

    private void offer(UUID store, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, sold_out_until, updated_at, "
                + "version) values (?,?,true,null,now(),0)", store, productId);
    }

    private void publish(UUID store, UUID productId, String channel, boolean visible, boolean orderable) {
        jdbc.update("insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, visible, "
                + "orderable, display_order, created_at, updated_at, version) values (?,?,?,?,?,?,0,now(),now(),0)",
                UUID.randomUUID(), store, productId, channel, visible, orderable);
    }

    private UUID newGroup(UUID product, String code, String name, String selectionType, int minimum) {
        UUID groupId = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_groups (group_id, group_code, group_name, selection_type, "
                + "minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,1,0,true,now(),now(),0)", groupId,
                code + "-" + UUID.randomUUID().toString().substring(0, 6), name, selectionType, minimum);
        jdbc.update("insert into catalog.product_customization_groups (product_id, group_id, required, display_order) "
                + "values (?,?,true,0)", product, groupId);
        return groupId;
    }

    private UUID newOption(UUID groupId, String code, String name, String price) {
        UUID optionId = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_options (option_id, group_id, option_code, option_name, "
                + "additional_price, display_order, active, created_at, updated_at, version) values (?,?,?,?,?,0,true,"
                + "now(),now(),0)", optionId, groupId,
                code + "-" + UUID.randomUUID().toString().substring(0, 6), name, new BigDecimal(price));
        return optionId;
    }

    /** Slice 4B fixtures: the per-product default lives on the V049 attachment row only. */
    private void setAttachmentDefault(UUID product, UUID groupId, UUID optionId) {
        jdbc.update("update catalog.product_customization_groups set default_option_id = ? where product_id = ? "
                + "and group_id = ?", optionId, product, groupId);
    }

    /** Slice 4B fixture: an explicit per-product allow-list row (subset write semantics). */
    private void allowOption(UUID product, UUID groupId, UUID optionId, boolean allowed) {
        jdbc.update("insert into catalog.product_customization_options (product_customization_option_id, product_id, "
                + "group_id, option_id, allowed, display_order, version, active) values (?,?,?,?,?,0,0,true)",
                UUID.randomUUID(), product, groupId, optionId, allowed);
    }

    private String staffToken(UUID roleId, UUID store) {
        UUID userId = UUID.randomUUID();
        String email = "cat-http-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Catalog HTTP Actor", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,?,?)", UUID.randomUUID(), userId, roleId, store,
                java.sql.Timestamp.from(Instant.now()));
        return tokens.access(userId, 0L, Set.of());
    }
}
