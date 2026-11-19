package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.ProductDetailsResponse;
import com.smartcoffee.modules.catalog.api.ProductSummaryResponse;
import com.smartcoffee.modules.catalog.application.ChannelCatalogService;
import com.smartcoffee.modules.catalog.application.ChannelContextResolver;
import com.smartcoffee.modules.catalog.domain.Channel;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Integration coverage for the Slice 3B channel-publication catalog (spec §8/§19/§23).
 * Unlike the pure unit tests, this drives the REAL V048 schema over Testcontainers, so
 * it proves the database-side guarantees mocks cannot: the deterministic legacy-visibility
 * inheritance (POS / CUSTOMER_WEB / CUSTOMER_APP keep pre-existing products even though
 * test fixtures are inserted AFTER the migration), deferred channels staying hidden,
 * hidden products being removed BEFORE pagination totals, list/detail verdict agreement,
 * trusted server-side channel resolution against the seeded RBAC, and that a catalog
 * read never writes.
 *
 * <p>Fixtures are created with fresh UUIDs and inserted directly via SQL, mirroring the
 * production backfill's "absent channel row == inherit legacy visibility" contract.
 */
class ChannelCatalogIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ChannelCatalogService catalog;
    @Autowired
    ChannelContextResolver resolver;
    @Autowired
    CurrentActorProvider actors;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------- fixtures

    private UUID newStore() {
        UUID id = UUID.randomUUID();
        String code = "CHST-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'CH Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", id, code);
        return id;
    }

    private UUID newCategory() {
        UUID id = UUID.randomUUID();
        String code = "CHCT-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, "
                + "display_order, active, created_at, updated_at, version) values (?,?,?,10,true,now(),now(),0)",
                id, code, "Cat " + code);
        return id;
    }

    private UUID newProduct(UUID categoryId, String name) {
        UUID id = UUID.randomUUID();
        String code = "CHPR-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD',5,0.100000,false,true,now(),now(),0)",
                id, code, categoryId, name, new BigDecimal("3.00"));
        return id;
    }

    private void offer(UUID storeId, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, station_code, override_price, "
                + "available, sold_out_until, updated_at, version) values (?,?,null,null,true,null,now(),0)", storeId,
                productId);
    }

    /** Inserts an EXPLICIT channel publication row (visible/orderable), overriding inheritance. */
    private void publish(UUID storeId, UUID productId, Channel channel, boolean visible, boolean orderable) {
        jdbc.update("insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, visible, "
                + "orderable, display_order, created_at, updated_at, version) values (?,?,?,?,?,?,0,now(),now(),0)",
                UUID.randomUUID(), storeId, productId, channel.name(), visible, orderable);
    }

    private static ProductSummaryResponse find(List<ProductSummaryResponse> rows, UUID productId) {
        return rows.stream().filter(r -> r.productId().equals(productId)).findFirst().orElse(null);
    }

    private List<ProductSummaryResponse> page(UUID store, Channel channel) {
        return catalog.list(store, channel, "", null, false, 0, 50).getContent();
    }

    // ------------------------------------------------- items 1/2/3: legacy inheritance

    @Test
    void legacyStoreProductAppearsInAllThreeIntegratedChannelsWithoutExplicitRow() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID p = newProduct(cat, "CH Legacy Latte");
        offer(store, p); // inserted AFTER migration -> no product_channels row -> must inherit

        assertNotNull(find(page(store, Channel.POS), p), "item 1: existing product preserved under POS");
        assertNotNull(find(page(store, Channel.CUSTOMER_WEB), p), "item 2: preserved under CUSTOMER_WEB");
        assertNotNull(find(page(store, Channel.CUSTOMER_APP), p),
                "item 3: CUSTOMER_APP follows the same safe legacy inheritance");
    }

    // --------------------------------------------- items 4/5/6: deferred channels hidden

    @Test
    void deferredChannelsAreHiddenByDefaultEvenWhenProductIsLegacyOffered() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID p = newProduct(cat, "CH Deferred");
        offer(store, p);

        assertTrue(page(store, Channel.QR_MENU).isEmpty(), "item 4: QR_MENU hidden by default");
        assertTrue(page(store, Channel.KIOSK).isEmpty(), "item 5: KIOSK hidden by default");
        assertTrue(page(store, Channel.DELIVERY).isEmpty(), "item 6: DELIVERY hidden by default");
        // ...while the same product is still visible on an integrated channel (sanity).
        assertNotNull(find(page(store, Channel.CUSTOMER_WEB), p));
    }

    // ------------------------------------------------ items 7/8: per-channel divergence

    @Test
    void productCanBePosVisibleAndCustomerHidden() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID p = newProduct(cat, "CH PosOnly");
        offer(store, p);
        publish(store, p, Channel.CUSTOMER_WEB, false, false); // explicitly hide on web

        assertNotNull(find(page(store, Channel.POS), p), "POS inherits visibility -> still listed");
        assertNull(find(page(store, Channel.CUSTOMER_WEB), p), "item 7: explicit hidden removes it from web");
    }

    @Test
    void productCanBeCustomerVisibleAndPosHidden() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID p = newProduct(cat, "CH WebOnly");
        offer(store, p);
        publish(store, p, Channel.POS, false, false); // explicitly hide on POS

        assertNotNull(find(page(store, Channel.CUSTOMER_WEB), p), "web inherits visibility -> still listed");
        assertNull(find(page(store, Channel.POS), p), "item 8: policy-permitted POS hide removes it");
    }

    // -------------------------------- item 12: public surface cannot reveal POS-only hidden product

    @Test
    void anonymousCustomerSurfaceNeverLeaksProductsHiddenOnThatChannel() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID hidden = newProduct(cat, "CH HiddenFromWeb");
        offer(store, hidden);
        publish(store, hidden, Channel.CUSTOMER_WEB, false, false);
        UUID shown = newProduct(cat, "CH ShownOnWeb");
        offer(store, shown);

        // The public path resolves CUSTOMER_WEB (no trusted staff context) and must exclude
        // the web-hidden product regardless of any caller-supplied channel hint (ignored).
        SecurityContextHolder.clearContext();
        assertEquals(Channel.CUSTOMER_WEB, resolver.resolve(), "anonymous resolves the customer web surface");
        List<ProductSummaryResponse> web = page(store, resolver.resolve());
        assertNull(find(web, hidden));
        assertNotNull(find(web, shown));
    }

    // -------------------------------------------- item 14: hidden removed BEFORE pagination totals

    @Test
    void hiddenProductsAreExcludedFromPaginationTotals() {
        UUID store = newStore();
        UUID cat = newCategory();
        for (int i = 1; i <= 3; i++) {
            offer(store, newProduct(cat, "CH Page " + i));
        }
        UUID hidden = newProduct(cat, "CH Page Hidden");
        offer(store, hidden);
        publish(store, hidden, Channel.CUSTOMER_WEB, false, false);

        var result = catalog.list(store, Channel.CUSTOMER_WEB, "CH Page", null, false, 0, 2);
        assertEquals(3, result.getTotalElements(), "the hidden product is not counted in the total");
        assertEquals(2, result.getContent().size(), "page size respected after visibility filtering");
        assertEquals(2, result.getTotalPages(), "totals reflect visible-only membership, not raw rows");
    }

    // ----------------------------------------- items 9/10/11: trusted resolver vs real RBAC

    @Test
    void staffActorResolvesPosCustomerResolvesWeb() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID p = newProduct(cat, "CH Resolve");
        offer(store, p);

        // Anonymous -> CUSTOMER_WEB (item 10).
        SecurityContextHolder.clearContext();
        assertEquals(Channel.CUSTOMER_WEB, resolver.resolve());

        // Seeded SUPER_ADMIN has global access -> POS (item 9).
        authenticateAs(SUPER_ADMIN);
        assertEquals(Channel.POS, resolver.resolve());

        // A plain user with no role/permission -> CUSTOMER_WEB; never auto-resolved to APP (item 11).
        authenticateAs(null);
        CurrentActor plain = actors.require();
        assertFalse(plain.globalAccess());
        assertEquals(Channel.CUSTOMER_WEB, resolver.resolve(),
                "CUSTOMER_APP has no distinct trusted signal and routes through the web projection");
    }

    private void authenticateAs(String roleId) {
        UUID userId = UUID.randomUUID();
        String email = "chan-it-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "CH User", email, email, "en-US");
        if (roleId != null) {
            jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                    + "assigned_at) values (?,?,?,null,now())", UUID.randomUUID(), userId, UUID.fromString(roleId));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ------------------------------------------------- item 16: visible sold-out stays listed

    @Test
    void soldOutProductRemainsListedButNotOrderable() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID p = newProduct(cat, "CH SoldOut");
        // store-product manual sold-out lever (available=false) via the raw fixture path
        jdbc.update("insert into catalog.store_products (store_id, product_id, station_code, override_price, "
                + "available, sold_out_until, updated_at, version) values (?,?,null,null,false,null,now(),0)", store, p);

        ProductSummaryResponse row = find(page(store, Channel.CUSTOMER_WEB), p);
        assertNotNull(row, "a sold-out product is still visible in the unfiltered catalog");
        assertTrue(row.visible());
        assertFalse(row.orderable(), "manual sold-out forces orderable=false");
    }

    // ------------------------------------------------- item 15: list and detail agree

    @Test
    void listAndDetailAgreeOnOrderabilityVerdict() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID p = newProduct(cat, "CH Agreement");
        offer(store, p);

        ProductSummaryResponse listRow = find(page(store, Channel.CUSTOMER_WEB), p);
        ProductDetailsResponse detail = catalog.detail(store, Channel.CUSTOMER_WEB, p);
        assertNotNull(listRow);
        assertEquals(listRow.visible(), detail.visible());
        assertEquals(listRow.orderable(), detail.orderable(), "list and detail must not contradict");
        assertEquals(listRow.inStock(), detail.inStock());
    }

    @Test
    void detailOfHiddenProductIsNotFoundOnPublicSurface() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID p = newProduct(cat, "CH DetailHidden");
        offer(store, p);
        publish(store, p, Channel.CUSTOMER_WEB, false, false);

        assertNull(find(page(store, Channel.CUSTOMER_WEB), p));
        assertThrows(ResourceNotFoundException.class, () -> catalog.detail(store, Channel.CUSTOMER_WEB, p),
                "hidden on the channel == 404 on the public detail, matching the list");
    }

    // ------------------------------------------------- item 21: catalog reads never write

    @Test
    void catalogReadsCreateNoDatabaseWrites() {
        UUID store = newStore();
        UUID cat = newCategory();
        UUID p = newProduct(cat, "CH NoWrite");
        offer(store, p);

        long channelsBefore = count("select count(*) from catalog.product_channels");
        long storeProductsBefore = count("select count(*) from catalog.store_products");
        long outboxBefore = count("select count(*) from integration.outbox_events");

        page(store, Channel.CUSTOMER_WEB);
        catalog.detail(store, Channel.CUSTOMER_WEB, p);
        page(store, Channel.POS);

        assertEquals(channelsBefore, count("select count(*) from catalog.product_channels"),
                "a read must not auto-create publication rows");
        assertEquals(storeProductsBefore, count("select count(*) from catalog.store_products"));
        assertEquals(outboxBefore, count("select count(*) from integration.outbox_events"),
                "catalog reads emit no domain events");
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0L : v;
    }

    @Test
    void unknownStoreStillFailsMembershipCheck() {
        assertThrows(ResourceNotFoundException.class,
                () -> catalog.list(UUID.randomUUID(), Channel.CUSTOMER_WEB, "", null, false, 0, 20));
    }
}
