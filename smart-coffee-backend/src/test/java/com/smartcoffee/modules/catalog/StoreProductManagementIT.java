package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.CreateStoreProductRequest;
import com.smartcoffee.modules.catalog.api.ManagementStoreProductResponse;
import com.smartcoffee.modules.catalog.api.UpdateStoreProductConfigurationRequest;
import com.smartcoffee.modules.catalog.application.StoreProductManagementService;
import com.smartcoffee.modules.ordering.application.CheckoutCatalogPort;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Integration tests for Checkpoints 3A/3B/3C — branch-product management and
 * the checkout catalog station/tax correction. Runs against a real migrated
 * database (Flyway V001-V017) with strict ddl-auto=validate.
 *
 * <p>Pre-seeded fixtures (V002/V003/V008):
 * <ul>
 * <li>Store BKK1: {@code 10000000-…-01}, currency USD</li>
 * <li>Products: ICED_LATTE ({@code 30000000-…-01}, 3.50 USD, tax 10%),
 * CROISSANT ({@code 30000000-…-02}, 2.25 USD, tax 10%)</li>
 * <li>Store-product rows for both at BKK1 (station BARISTA/BAKERY)</li>
 * <li>Stations: BARISTA, KITCHEN, BAKERY (all at BKK1, active)</li>
 * <li>Categories: COFFEE, BAKERY</li>
 * <li>Roles: SUPER_ADMIN, STORE_MANAGER, CASHIER</li>
 * </ul>
 */
@SuppressWarnings("null")
class StoreProductManagementIT extends IntegrationTestBase {

    private static final UUID BKK1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ICED_LATTE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CROISSANT = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID COFFEE_CAT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID BAKERY_CAT = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired StoreProductManagementService service;
    @Autowired CheckoutCatalogPort checkoutCatalog;

    private CurrentActor adminActor;
    private UUID adminUserId;

    @BeforeEach
    void seedAdmin() {
        adminUserId = seedUserWithRole("IT-Admin-" + UUID.randomUUID(), "admin", "00000000-0000-0000-0000-000000000002");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminUserId.toString(), null, List.of()));
        adminActor = actors.require();
    }

    private UUID seedUserWithRole(String email, String prefix, String roleId) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, prefix + " User", email + "@smart.coffee", email + "@smart.coffee", "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?, null, ?)", UUID.randomUUID(), userId, UUID.fromString(roleId),
                java.sql.Timestamp.from(Instant.now()));
        return userId;
    }

    private UUID seedStoreScopedUser(String roleId, UUID storeId) {
        UUID userId = UUID.randomUUID();
        String email = "store-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Store User", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?, ?, ?)", UUID.randomUUID(), userId, UUID.fromString(roleId), storeId,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return userId;
    }

    // ============================================================ CP3A READ

    @Nested
    class ReadApi {

        @Test
        void listReturnsSeededBranchProducts() {
            var page = service.list(adminActor, BKK1, "", null, null, null, null, null, null, null, null, 0, 20);
            assertTrue(page.getTotalElements() >= 2, "At least ICED_LATTE and CROISSANT should be listed");
            var names = page.getContent().stream().map(ManagementStoreProductResponse::productName).toList();
            assertTrue(names.contains("Iced Latte"));
            assertTrue(names.contains("Butter Croissant"));
        }

        @Test
        void inheritedAndEffectivePrice() {
            var resp = service.get(adminActor, BKK1, CROISSANT);
            assertTrue(resp.inheritedPrice(), "CROISSANT has no override");
            assertEquals(new BigDecimal("2.2500"), resp.basePrice());
            assertEquals(new BigDecimal("2.2500"), resp.effectivePrice());
        }

        @Test
        void overridePriceReflectedInEffective() {
            // Set an override via the management service
            var existing = service.get(adminActor, BKK1, CROISSANT);
            service.updateConfiguration(adminActor, BKK1, CROISSANT,
                    new UpdateStoreProductConfigurationRequest(new BigDecimal("3.00"), true, null, "BAKERY",
                            existing.storeProductVersion()));
            var updated = service.get(adminActor, BKK1, CROISSANT);
            assertFalse(updated.inheritedPrice());
            assertEquals(new BigDecimal("3.0000"), updated.effectivePrice());
            assertEquals(new BigDecimal("2.2500"), updated.basePrice());
        }

        @Test
        void soldOutUntilMakesEffectiveUnavailable() {
            var existing = service.get(adminActor, BKK1, ICED_LATTE);
            Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
            service.updateConfiguration(adminActor, BKK1, ICED_LATTE,
                    new UpdateStoreProductConfigurationRequest(null, true, future, "BARISTA",
                            existing.storeProductVersion()));
            var resp = service.get(adminActor, BKK1, ICED_LATTE);
            assertTrue(resp.branchAvailable());
            assertFalse(resp.effectiveAvailable(), "soldOutUntil in the future means effectively unavailable");
            assertEquals(future.truncatedTo(ChronoUnit.SECONDS), resp.soldOutUntil().truncatedTo(ChronoUnit.SECONDS));
        }

        @Test
        void filterByAvailability() {
            // Mark CROISSANT unavailable
            var existing = service.get(adminActor, BKK1, CROISSANT);
            service.updateConfiguration(adminActor, BKK1, CROISSANT,
                    new UpdateStoreProductConfigurationRequest(null, false, null, "BAKERY",
                            existing.storeProductVersion()));

            var unavailable = service.list(adminActor, BKK1, "", null, null, null, "UNAVAILABLE", null, null, null, null, 0, 20);
            assertTrue(unavailable.getContent().stream().allMatch(p -> !p.effectiveAvailable()));
            assertTrue(unavailable.getContent().stream().anyMatch(p -> p.productId().equals(CROISSANT)));

            // Restore
            service.updateConfiguration(adminActor, BKK1, CROISSANT,
                    new UpdateStoreProductConfigurationRequest(null, true, null, "BAKERY",
                            service.get(adminActor, BKK1, CROISSANT).storeProductVersion()));
        }

        @Test
        void filterByOverrideState() {
            // Add an override to ICED_LATTE
            var existing = service.get(adminActor, BKK1, ICED_LATTE);
            service.updateConfiguration(adminActor, BKK1, ICED_LATTE,
                    new UpdateStoreProductConfigurationRequest(new BigDecimal("4.00"), true, null, "BARISTA",
                            existing.storeProductVersion()));

            var overrides = service.list(adminActor, BKK1, "", null, null, null, null, "OVERRIDE", null, null, null, 0, 20);
            assertTrue(overrides.getContent().stream().allMatch(p -> !p.inheritedPrice()));
            assertTrue(overrides.getContent().stream().anyMatch(p -> p.productId().equals(ICED_LATTE)));

            var inherited = service.list(adminActor, BKK1, "", null, null, null, null, "INHERITED", null, null, null, 0, 20);
            assertTrue(inherited.getContent().stream().allMatch(ManagementStoreProductResponse::inheritedPrice));

            // Clear override
            service.updateConfiguration(adminActor, BKK1, ICED_LATTE,
                    new UpdateStoreProductConfigurationRequest(null, true, null, "BARISTA",
                            service.get(adminActor, BKK1, ICED_LATTE).storeProductVersion()));
        }

        @Test
        void filterByCategory() {
            var bakery = service.list(adminActor, BKK1, "", BAKERY_CAT, null, null, null, null, null, null, null, 0, 20);
            assertTrue(bakery.getContent().stream().allMatch(p -> BAKERY_CAT.equals(p.categoryId())));
        }

        @Test
        void filterByStation() {
            var barista = service.list(adminActor, BKK1, "", null, null, null, null, null, "BARISTA", null, null, 0, 20);
            assertTrue(barista.getContent().stream().allMatch(p -> "BARISTA".equals(p.stationCode())));
        }

        @Test
        void searchByName() {
            var results = service.list(adminActor, BKK1, "Croissant", null, null, null, null, null, null, null, null, 0, 20);
            assertEquals(1, results.getTotalElements());
            assertEquals(CROISSANT, results.getContent().get(0).productId());
        }

        @Test
        void pagination() {
            var page = service.list(adminActor, BKK1, "", null, null, null, null, null, null, "productName", "asc", 0, 1);
            assertEquals(1, page.getSize());
            assertEquals(1, page.getContent().size());
            assertTrue(page.getTotalElements() >= 2);
            assertTrue(page.getTotalPages() >= 2);
        }

        @Test
        void invalidSortRejected() {
            var ex = assertThrows(ValidationException.class,
                    () -> service.list(adminActor, BKK1, "", null, null, null, null, null, null, "invalidField", "asc", 0, 20));
            assertEquals("INVALID_SORT", ex.code());
        }

        @Test
        void invalidAvailabilityFilterRejected() {
            var ex = assertThrows(ValidationException.class,
                    () -> service.list(adminActor, BKK1, "", null, null, null, "BOGUS", null, null, null, null, 0, 20));
            assertEquals("INVALID_AVAILABILITY_FILTER", ex.code());
        }

        @Test
        void globalAdminAccessesAnyBranch() {
            assertTrue(adminActor.globalAccess());
            // Accessing seeded BKK1 succeeds
            var page = service.list(adminActor, BKK1, "", null, null, null, null, null, null, null, null, 0, 20);
            assertNotNull(page);
        }

        @Test
        void wrongBranchAccessDenied() {
            // Create a store-scoped STORE_MANAGER for a DIFFERENT store
            UUID otherStore = createTestStore("OTHER-" + UUID.randomUUID().toString().substring(0, 6));
            seedStoreScopedUser("00000000-0000-0000-0000-000000000004", otherStore);
            CurrentActor scopedActor = actors.require();

            var ex = assertThrows(ForbiddenException.class,
                    () -> service.list(scopedActor, BKK1, "", null, null, null, null, null, null, null, null, 0, 20));
            assertEquals("STORE_ACCESS_DENIED", ex.code());
        }
    }

    // ======================================================== CP3B MUTATIONS

    @Nested
    class CreateApi {

        @Test
        void addProductToBranchAndAudit() {
            // Create a new product via JDBC then add it
            UUID newProductId = seedProduct("TEST_" + UUID.randomUUID().toString().substring(0, 6), COFFEE_CAT,
                    "Test Espresso", "USD", "3.75", "0.100000", 5);

            var resp = service.add(adminActor, BKK1,
                    new CreateStoreProductRequest(newProductId, null, true, null, "BARISTA"));

            assertEquals(newProductId, resp.productId());
            assertTrue(resp.inheritedPrice());
            assertEquals(new BigDecimal("3.7500"), resp.effectivePrice());
            assertEquals("BARISTA", resp.stationCode());

            // Audit log must exist
            Integer count = jdbc.queryForObject(
                    "select count(*) from audit.audit_logs where action='BRANCH_PRODUCT_ADDED' and aggregate_id=?",
                    Integer.class, newProductId);
            assertTrue(count > 0, "Audit record should be written");
        }

        @Test
        void duplicateConfigurationRejected() {
            var ex = assertThrows(ConflictException.class,
                    () -> service.add(adminActor, BKK1,
                            new CreateStoreProductRequest(ICED_LATTE, null, true, null, "BARISTA")));
            assertEquals("CONFIGURATION_EXISTS", ex.code());
        }

        @Test
        void negativePriceRejected() {
            UUID pid = seedProduct("NEG_" + UUID.randomUUID().toString().substring(0, 6), COFFEE_CAT,
                    "Neg", "USD", "1.00", "0", 1);
            var ex = assertThrows(ValidationException.class,
                    () -> service.add(adminActor, BKK1,
                            new CreateStoreProductRequest(pid, new BigDecimal("-1.00"), true, null, "BARISTA")));
            assertEquals("NEGATIVE_PRICE", ex.code());
        }

        @Test
        void excessivePrecisionRejected() {
            UUID pid = seedProduct("PREC_" + UUID.randomUUID().toString().substring(0, 6), COFFEE_CAT,
                    "Prec", "USD", "1.00", "0", 1);
            var ex = assertThrows(ValidationException.class,
                    () -> service.add(adminActor, BKK1,
                            new CreateStoreProductRequest(pid, new BigDecimal("1.00001"), true, null, "BARISTA")));
            assertEquals("PRICE_SCALE_EXCEEDED", ex.code());
        }

        @Test
        void crossBranchStationRejected() {
            // Create a new store and station — cannot use that station at BKK1
            UUID store2 = createTestStore("STN2-" + UUID.randomUUID().toString().substring(0, 6));
            seedStation(store2, "KITCHEN2", "KITCHEN");
            UUID pid = seedProduct("XSTN_" + UUID.randomUUID().toString().substring(0, 6), COFFEE_CAT,
                    "XStn", "USD", "1.00", "0", 1);

            var ex = assertThrows(ValidationException.class,
                    () -> service.add(adminActor, BKK1,
                            new CreateStoreProductRequest(pid, null, true, null, "KITCHEN2")));
            assertEquals("STATION_NOT_FOUND", ex.code());
        }

        @Test
        void inactiveStationRejected() {
            UUID store3 = createTestStore("INCT_" + UUID.randomUUID().toString().substring(0, 6));
            seedStationWithActive(store3, "OFFLINE", "BARISTA", false);
            UUID pid = seedProduct("INCT_" + UUID.randomUUID().toString().substring(0, 8), COFFEE_CAT,
                    "IncStn", "USD", "1.00", "0", 1);

            var ex = assertThrows(ValidationException.class,
                    () -> service.add(adminActor, store3,
                            new CreateStoreProductRequest(pid, null, true, null, "OFFLINE")));
            assertEquals("STATION_INACTIVE", ex.code());
        }

        @Test
        void productNotFound() {
            var ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.add(adminActor, BKK1,
                            new CreateStoreProductRequest(UUID.randomUUID(), null, true, null, "BARISTA")));
            assertEquals("PRODUCT_NOT_FOUND", ex.code());
        }
    }

    @Nested
    class UpdateApi {

        @Test
        void staleVersionRejected() {
            var existing = service.get(adminActor, BKK1, ICED_LATTE);
            var ex = assertThrows(ConflictException.class,
                    () -> service.updateConfiguration(adminActor, BKK1, ICED_LATTE,
                            new UpdateStoreProductConfigurationRequest(null, true, null, "BARISTA",
                                    existing.storeProductVersion() + 99)));
            assertEquals("VERSION_CONFLICT", ex.code());
        }

        @Test
        void clearOverrideAudited() {
            // First set an override
            var existing = service.get(adminActor, BKK1, ICED_LATTE);
            service.updateConfiguration(adminActor, BKK1, ICED_LATTE,
                    new UpdateStoreProductConfigurationRequest(new BigDecimal("5.00"), true, null, "BARISTA",
                            existing.storeProductVersion()));

            // Now clear it
            var withOverride = service.get(adminActor, BKK1, ICED_LATTE);
            service.updateConfiguration(adminActor, BKK1, ICED_LATTE,
                    new UpdateStoreProductConfigurationRequest(null, true, null, "BARISTA",
                            withOverride.storeProductVersion()));

            Integer count = jdbc.queryForObject(
                    "select count(*) from audit.audit_logs where action='BRANCH_PRODUCT_OVERRIDE_CLEARED' and aggregate_id=?",
                    Integer.class, ICED_LATTE);
            assertTrue(count > 0, "Clear override must be audited");

            var after = service.get(adminActor, BKK1, ICED_LATTE);
            assertTrue(after.inheritedPrice());
        }

        @Test
        void stationChangeAudited() {
            var existing = service.get(adminActor, BKK1, ICED_LATTE);
            service.updateConfiguration(adminActor, BKK1, ICED_LATTE,
                    new UpdateStoreProductConfigurationRequest(null, true, null, "KITCHEN",
                            existing.storeProductVersion()));

            Integer count = jdbc.queryForObject(
                    "select count(*) from audit.audit_logs where action='BRANCH_PRODUCT_STATION_UPDATED' and aggregate_id=?",
                    Integer.class, ICED_LATTE);
            assertTrue(count > 0);

            var resp = service.get(adminActor, BKK1, ICED_LATTE);
            assertEquals("KITCHEN", resp.stationCode());

            // Restore to BARISTA
            service.updateConfiguration(adminActor, BKK1, ICED_LATTE,
                    new UpdateStoreProductConfigurationRequest(null, true, null, "BARISTA",
                            resp.storeProductVersion()));
        }
    }

    // ================================================= CP3C CHECKOUT CATALOG

    @Nested
    class CheckoutCatalogValidation {

        @Test
        void stationFromBranchProductNotNull() {
            // CROISSANT is seeded at BKK1 with station BAKERY
            var validated = checkoutCatalog.validate(BKK1, CROISSANT, 1, List.of());
            assertEquals("BAKERY", validated.stationCode());
        }

        @Test
        void taxRateFromCatalog() {
            // ICED_LATTE has 0.100000 tax and is customizable (requires milk+sugar)
            var milkGroupId = UUID.fromString("40000000-0000-0000-0000-000000000001");
            var freshMilkId = UUID.fromString("50000000-0000-0000-0000-000000000001");
            var sugarGroupId = UUID.fromString("40000000-0000-0000-0000-000000000002");
            var normalSugarId = UUID.fromString("50000000-0000-0000-0000-000000000003");
            var options = List.of(
                    new CheckoutCatalogPort.SelectedOption(milkGroupId, freshMilkId, 1),
                    new CheckoutCatalogPort.SelectedOption(sugarGroupId, normalSugarId, 1));

            var validated = checkoutCatalog.validate(BKK1, ICED_LATTE, 1, options);
            // tax_rate is 0.100000 from catalog.products
            assertEquals(0, new BigDecimal("0.100000").compareTo(validated.taxRate()));
            assertEquals("BARISTA", validated.stationCode());
        }

        @Test
        void priceReflectsOverride() {
            // Add an override for CROISSANT
            var existing = service.get(adminActor, BKK1, CROISSANT);
            service.updateConfiguration(adminActor, BKK1, CROISSANT,
                    new UpdateStoreProductConfigurationRequest(new BigDecimal("9.99"), true, null, "BAKERY",
                            existing.storeProductVersion()));

            var validated = checkoutCatalog.validate(BKK1, CROISSANT, 1, List.of());
            assertEquals(new BigDecimal("9.9900"), validated.basePrice());

            // Restore
            service.updateConfiguration(adminActor, BKK1, CROISSANT,
                    new UpdateStoreProductConfigurationRequest(null, true, null, "BAKERY",
                            service.get(adminActor, BKK1, CROISSANT).storeProductVersion()));
        }

        @Test
        void unavailableProductRejectedAtCheckout() {
            var existing = service.get(adminActor, BKK1, CROISSANT);
            service.updateConfiguration(adminActor, BKK1, CROISSANT,
                    new UpdateStoreProductConfigurationRequest(null, false, null, "BAKERY",
                            existing.storeProductVersion()));

            var ex = assertThrows(ConflictException.class,
                    () -> checkoutCatalog.validate(BKK1, CROISSANT, 1, List.of()));
            assertEquals("PRODUCT_UNAVAILABLE", ex.code());

            // Restore
            service.updateConfiguration(adminActor, BKK1, CROISSANT,
                    new UpdateStoreProductConfigurationRequest(null, true, null, "BAKERY",
                            service.get(adminActor, BKK1, CROISSANT).storeProductVersion()));
        }
    }

    // =========================================================== HELPERS

    private UUID createTestStore(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'IT Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", id, code);
        return id;
    }

    private UUID seedProduct(String productCode, UUID categoryId, String name, String currency,
            String basePrice, String taxRate, int prepMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,?,?,? ,false,true,now(),now(),0)",
                id, productCode, categoryId, name, new BigDecimal(basePrice), currency, prepMinutes,
                new BigDecimal(taxRate));
        return id;
    }

    private void seedStation(UUID storeId, String code, String type) {
        jdbc.update("insert into production.production_stations (station_id, store_id, station_code, station_name, "
                + "station_type, display_order, active, version) values (?,?,?,? ,?,10,true,0)",
                UUID.randomUUID(), storeId, code, code, type);
    }

    private void seedStationWithActive(UUID storeId, String code, String type, boolean active) {
        jdbc.update("insert into production.production_stations (station_id, store_id, station_code, station_name, "
                + "station_type, display_order, active, version) values (?,?,?,?,?,10,?,0)",
                UUID.randomUUID(), storeId, code, code, type, active);
    }
}
