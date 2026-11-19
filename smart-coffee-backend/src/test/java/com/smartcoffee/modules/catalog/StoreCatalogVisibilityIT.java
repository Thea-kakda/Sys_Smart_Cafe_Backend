package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.catalog.api.ProductSummaryResponse;
import com.smartcoffee.modules.catalog.application.StoreCatalogQueryService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration coverage for the PUBLIC customer catalog read
 * ({@link StoreCatalogQueryService#list} -> StoreProductRepository.catalog),
 * the exact surface the Customer Web menu depends on. The management read path
 * is already covered by {@code StoreProductManagementIT}; this class locks the
 * customer visibility rules so a future query change cannot silently leak or
 * hide menu items:
 *
 * <ul>
 * <li>only products the selected store actually offers appear (store membership);</li>
 * <li>inactive products and inactive categories are excluded;</li>
 * <li>the effective price uses the store override when present, else the base price;</li>
 * <li>availability is the effective rule (branch available AND no active sold-out
 * window), and {@code availableOnly} drops sold-out rows entirely;</li>
 * <li>category filter and search narrow the result set;</li>
 * <li>pagination metadata is authoritative (totalElements/totalPages), and an
 * unknown or inactive store fails rather than returning an empty page.</li>
 * </ul>
 *
 * Every fixture is created with fresh UUIDs in the isolated Testcontainers
 * database, so this class neither depends on nor mutates the shared BKK1 seed.
 */
class StoreCatalogVisibilityIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StoreCatalogQueryService catalog;

    private UUID newStore(boolean active) {
        UUID id = UUID.randomUUID();
        String code = "SCVS-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'SCV Store','Addr','Asia/Phnom_Penh','USD','en-US',?,now(),now(),0)", id, code, active);
        return id;
    }

    private UUID newCategory(boolean active) {
        UUID id = UUID.randomUUID();
        String code = "SCVC-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, "
                + "display_order, active, created_at, updated_at, version) values (?,?,?,10,?,now(),now(),0)",
                id, code, "Cat " + code, active);
        return id;
    }

    private UUID newProduct(UUID categoryId, String name, String basePrice, boolean active) {
        UUID id = UUID.randomUUID();
        String code = "SCVP-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD',5,0.100000,false,?,now(),now(),0)",
                id, code, categoryId, name, new BigDecimal(basePrice), active);
        return id;
    }

    private void offer(UUID storeId, UUID productId, boolean available, Instant soldOutUntil, BigDecimal override) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, station_code, override_price, "
                + "available, sold_out_until, updated_at, version) values (?, ?, null, ?, ?, ?, now(), 0)", ps -> {
            ps.setObject(1, storeId);
            ps.setObject(2, productId);
            if (override == null) {
                ps.setNull(3, Types.DECIMAL);
            } else {
                ps.setBigDecimal(3, override);
            }
            ps.setBoolean(4, available);
            if (soldOutUntil == null) {
                ps.setNull(5, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(5, java.sql.Timestamp.from(soldOutUntil));
            }
        });
    }

    private static ProductSummaryResponse find(List<ProductSummaryResponse> rows, UUID productId) {
        return rows.stream().filter(r -> r.productId().equals(productId)).findFirst().orElse(null);
    }

    /** Same lookup for the rows a test dereferences: fails loudly instead of an NPE. */
    private static ProductSummaryResponse require(List<ProductSummaryResponse> rows, UUID productId) {
        ProductSummaryResponse row = find(rows, productId);
        if (row == null) {
            throw new AssertionError("product " + productId + " is missing from the catalog page");
        }
        return row;
    }

    @org.junit.jupiter.api.Test
    void offeredActiveProductsVisible_inactiveAndSoldOutRulesEnforced() {
        UUID store = newStore(true);
        UUID cat = newCategory(true);

        UUID active = newProduct(cat, "SCV Espresso", "3.00", true);
        offer(store, active, true, null, new BigDecimal("8.88")); // override price
        UUID soldOut = newProduct(cat, "SCV Cold Brew", "4.00", true);
        offer(store, soldOut, true, Instant.now().plus(2, ChronoUnit.HOURS), null); // sold out soon
        UUID inactiveProduct = newProduct(cat, "SCV Retired", "5.00", false);
        offer(store, inactiveProduct, true, null, null); // product active=false
        UUID deadCat = newCategory(false);
        UUID inactiveCategoryProduct = newProduct(deadCat, "SCV Ghost", "6.00", true);
        offer(store, inactiveCategoryProduct, true, null, null); // category active=false

        var full = catalog.list(store, "", null, false, 0, 50).getContent();
        assertNotNull(find(full, active), "offered active product must be listed");
        assertNotNull(find(full, soldOut), "sold-out product is still listed in the unfiltered view");
        assertFalse(require(full, soldOut).available(), "sold-out product reports effective availability=false");
        assertNull(find(full, inactiveProduct), "inactive product is never exposed to customers");
        assertNull(find(full, inactiveCategoryProduct), "product under an inactive category is never exposed");

        assertEquals(0, new BigDecimal("8.8800").compareTo(require(full, active).price()),
                "customer price uses the store override resolved by the backend");

        var availableOnly = catalog.list(store, "", null, true, 0, 50).getContent();
        assertNotNull(find(availableOnly, active), "in-stock product remains under availableOnly");
        assertNull(find(availableOnly, soldOut), "availableOnly drops the sold-out product entirely");
        assertNull(find(availableOnly, inactiveProduct), "availableOnly still hides inactive products");
        assertNull(find(availableOnly, inactiveCategoryProduct), "availableOnly still hides inactive-category products");
    }

    @org.junit.jupiter.api.Test
    void inheritedBasePriceWhenStoreHasNoOverride() {
        UUID store = newStore(true);
        UUID cat = newCategory(true);
        UUID p = newProduct(cat, "SCV Plain Drip", "3.00", true);
        offer(store, p, true, null, null); // no override -> inherits base price

        var row = require(catalog.list(store, "", null, false, 0, 50).getContent(), p);
        assertEquals(0, new BigDecimal("3.0000").compareTo(row.price()), "inherited base price is surfaced");
    }

    @org.junit.jupiter.api.Test
    void productNotOfferedByThisStoreIsAbsent() {
        UUID storeA = newStore(true);
        UUID storeB = newStore(true);
        UUID cat = newCategory(true);
        UUID p = newProduct(cat, "SCV StoreB Only", "3.00", true);
        offer(storeB, p, true, null, null); // only store B carries it

        assertNull(find(catalog.list(storeA, "", null, false, 0, 50).getContent(), p),
                "a product a store does not offer must not appear in that store's menu");
        assertNotNull(find(catalog.list(storeB, "", null, false, 0, 50).getContent(), p));
    }

    @org.junit.jupiter.api.Test
    void categoryFilterAndSearchNarrowResults() {
        UUID store = newStore(true);
        UUID catA = newCategory(true);
        UUID catB = newCategory(true);
        UUID alpha = newProduct(catA, "SCV Alpha Latte", "3.00", true);
        offer(store, alpha, true, null, null);
        UUID beta = newProduct(catB, "SCV Beta Cake", "4.00", true);
        offer(store, beta, true, null, null);

        var onlyCatA = catalog.list(store, "", catA, false, 0, 50).getContent();
        assertTrue(onlyCatA.stream().allMatch(r -> catA.equals(r.categoryId())));
        assertNotNull(find(onlyCatA, alpha));
        assertNull(find(onlyCatA, beta));

        var searched = catalog.list(store, "Latte", null, false, 0, 50).getContent();
        assertEquals(1, searched.size(), "search matches by product name substring");
        assertEquals(alpha, searched.get(0).productId());
    }

    @org.junit.jupiter.api.Test
    void paginationMetadataIsAuthoritative() {
        UUID store = newStore(true);
        UUID cat = newCategory(true);
        for (int i = 1; i <= 3; i++) {
            UUID p = newProduct(cat, "SCV Page Item " + i, "3.00", true);
            offer(store, p, true, null, null);
        }
        var page0 = catalog.list(store, "", null, false, 0, 2);
        assertEquals(2, page0.getContent().size(), "page size is respected");
        assertEquals(3, page0.getTotalElements(), "totalElements reflects the whole result, not the page");
        assertEquals(2, page0.getTotalPages());
        assertTrue(page0.isFirst());
        assertFalse(page0.isLast());
    }

    @org.junit.jupiter.api.Test
    void unknownOrInactiveStoreFailsRatherThanReturningEmpty() {
        assertThrows(ResourceNotFoundException.class,
                () -> catalog.list(UUID.randomUUID(), "", null, false, 0, 20),
                "an unknown store must not masquerade as an empty menu");

        UUID closedStore = newStore(false);
        assertThrows(ResourceNotFoundException.class,
                () -> catalog.list(closedStore, "", null, false, 0, 20),
                "an inactive store must fail the store membership check");
    }
}
