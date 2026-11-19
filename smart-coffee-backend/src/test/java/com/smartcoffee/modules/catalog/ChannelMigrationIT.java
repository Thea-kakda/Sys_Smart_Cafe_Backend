package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.testsupport.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Formal V048 migration verification (Slice 3B.1, spec §5) on BOTH a fresh and a
 * genuinely populated database — never inferred from a fresh context boot alone.
 *
 * <p>A) FRESH: the application's own Testcontainers database has already been migrated
 * V001..V048 by Flyway at boot (with {@code validate-on-migrate: true}), so a programmatic
 * {@link Flyway#validate()} re-confirms every V001..V047 checksum is unchanged, and the
 * schema/constraint/index/permission-seed assertions prove the fresh target state.
 *
 * <p>B) POPULATED: a throwaway Postgres container is migrated to V047 only via Flyway
 * {@code target}, seeded with representative pre-existing products / store products
 * (active, available, customer-visible), then V048 is applied by a full migrate. The test
 * asserts the additive backfill publishes POS/CUSTOMER_WEB/CUSTOMER_APP for existing store
 * products, leaves QR_MENU/KIOSK/DELIVERY hidden, keeps every product id / store-product
 * row / price unchanged, seeds the two permissions exactly once without id collision, and
 * is idempotent (re-running the backfill adds nothing).
 */
@SpringBootTest
class ChannelMigrationIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate appJdbc;
    @Autowired
    DataSource appDataSource;

    // =============================================================== A. FRESH DB

    @Test
    void freshDatabaseReachesV048WithValidChecksumsAndExpectedObjects() {
        Flyway flyway = Flyway.configure().dataSource(appDataSource).locations("classpath:db/migration").load();
        assertDoesNotThrow(flyway::validate, "V001..V048 checksums unchanged and the head valid on the fresh boot");

        // V048 (channels) is validated here; the current head is V050 (Slice 4B.1
        // variant identity snapshots). This pin tracks the head so it fails loudly if
        // a further migration is added unnoticed. V050 objects are asserted by
        // CartVariantPricingIT.v050AddsNullableVariantSnapshotColumns.
        // V051 (Slice 5A production orders); V052 (SUPER_ADMIN RBAC hardening);
        // V053 (Phase B2 promotion checkout reservation) is the current head.
        assertEquals("053", appJdbc.queryForObject(
                "select max(version) from flyway_schema_history where success and version is not null", String.class));
        assertEquals(1, appJdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '048'", Integer.class),
                "V048 applied exactly once");

        assertNotNull(appJdbc.queryForObject("select to_regclass('catalog.product_channels')::text", String.class));
        assertNotNull(appJdbc.queryForObject("select to_regclass('catalog.product_channel_schedules')::text",
                String.class));
        assertEquals(1, count("select count(*) from information_schema.table_constraints where "
                + "constraint_name = 'uk_product_channel'"), "unique store+product+channel constraint");
        assertEquals(1, count("select count(*) from information_schema.table_constraints where "
                + "constraint_name = 'ck_channel_schedule_span'"), "schedule span constraint");
        assertEquals(1, count("select count(*) from pg_indexes where indexname = 'ix_product_channel_catalog'"),
                "catalog scan index");

        assertEquals(2, count("select count(*) from identity.permissions where permission_code in "
                + "('product-channel:read','product-channel:manage')"), "channel permissions seeded once");
        assertEquals(1, count("select count(*) from identity.permissions where permission_id = "
                + "'e6000000-0000-0000-0000-000000000001'::uuid"), "seed id is a single row");
    }

    // =============================================================== B. POPULATED V047 -> V048

    @Test
    void populatedV047DatabaseUpgradesToV048WithAdditiveBackfillAndNoDataLoss() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(),
                    pg.getPassword());

            // 1. Apply only through V047.
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("47").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertEquals(1, count(jdbc, "select count(*) from flyway_schema_history where version = '047'"));
            assertEquals(0, count(jdbc, "select count(*) from flyway_schema_history where version = '048'"),
                    "V048 not yet applied");

            // 2. Seed representative PRE-EXISTING state.
            UUID store = UUID.randomUUID();
            jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                    + "default_currency, default_language, active, created_at, updated_at, version) "
                    + "values (?,?,'MIG Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", store,
                    "MI" + store.toString().substring(0, 8));
            UUID cat = firstCategory(jdbc);
            UUID activeAvailable = seedProduct(jdbc, cat, "MIG-ACTIVE", "4.00");
            UUID activeUnavailable = seedProduct(jdbc, cat, "MIG-INAV", "5.50");
            seedStoreProduct(jdbc, store, activeAvailable, true);
            seedStoreProduct(jdbc, store, activeUnavailable, false);

            List<UUID> idsBefore = jdbc.queryForList("select product_id from catalog.products order by product_id",
                    UUID.class);
            long spRowsBefore = count(jdbc, "select count(*) from catalog.store_products");
            java.math.BigDecimal priceBefore = jdbc.queryForObject(
                    "select base_price from catalog.products where product_code = 'MIG-ACTIVE'",
                    java.math.BigDecimal.class);

            // 3. Apply V048 (full migrate now runs 48 only).
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            assertEquals(1, count(jdbc, "select count(*) from flyway_schema_history where version = '048'"),
                    "V048 applied to the populated DB exactly once");

            // 4a. No data loss: ids, store-product rows, prices untouched.
            assertEquals(idsBefore, jdbc.queryForList("select product_id from catalog.products order by product_id",
                    UUID.class), "existing product ids unchanged");
            assertEquals(spRowsBefore, count(jdbc, "select count(*) from catalog.store_products"),
                    "existing store-product rows unchanged");
            assertEquals(0, priceBefore.compareTo(jdbc.queryForObject(
                    "select base_price from catalog.products where product_code = 'MIG-ACTIVE'",
                    java.math.BigDecimal.class)), "existing prices unchanged by the migration");

            // 4b. Backfill: each pre-existing store product is published for the three
            // integrated channels and hidden on the deferred three.
            for (UUID p : List.of(activeAvailable, activeUnavailable)) {
                assertEquals(3, count(jdbc, "select count(*) from catalog.product_channels where store_id=? and "
                        + "product_id=? and channel in ('POS','CUSTOMER_WEB','CUSTOMER_APP')", store, p),
                        "POS/WEB/APP backfilled for an existing store product");
                assertEquals(0, count(jdbc, "select count(*) from catalog.product_channels where store_id=? and "
                        + "product_id=? and channel in ('QR_MENU','KIOSK','DELIVERY')", store, p),
                        "deferred channels stay unbackfilled (hidden)");
                assertEquals(3, count(jdbc, "select count(*) from catalog.product_channels where store_id=? and "
                        + "product_id=?", store, p), "no duplicate channel rows");
            }

            // 4c. POS and CUSTOMER_WEB legacy visibility preserved: the old store product is
            // still visible for those channels (the migration backfills visible=true).
            assertEquals(2, count(jdbc, "select count(*) from catalog.product_channels where store_id=? and "
                    + "product_id=? and channel in ('POS','CUSTOMER_WEB') and visible", store, activeAvailable));

            // 4d. Permission seeds present exactly once, ids not colliding.
            assertEquals(2, count(jdbc, "select count(*) from identity.permissions where permission_id in "
                    + "('e6000000-0000-0000-0000-000000000001'::uuid,'e6000000-0000-0000-0000-000000000002'::uuid)"));
            assertTrue(count(jdbc, "select count(*) from identity.role_permissions rp join identity.permissions p "
                    + "on p.permission_id=rp.permission_id where p.permission_code like 'product-channel:%'") > 0);

            // 4e. Idempotency: re-running the backfill INSERT adds nothing (ON CONFLICT DO NOTHING).
            jdbc.update("insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, "
                    + "visible, orderable, display_order, created_at, updated_at, version) "
                    + "select ('00000000-0000-0000-0000-000000000000'::uuid), sp.store_id, sp.product_id, ch.channel, "
                    + "true, true, 0, now(), now(), 0 from catalog.store_products sp cross join "
                    + "(values ('POS'),('CUSTOMER_WEB'),('CUSTOMER_APP')) ch(channel) "
                    + "on conflict (store_id, product_id, channel) do nothing");
            assertEquals(6, count(jdbc, "select count(*) from catalog.product_channels where store_id=?", store),
                    "two store products x three channels, unchanged by a re-run");
        }
    }

    // ------------------------------------------------------------------ helpers

    private int count(JdbcTemplate jdbc, String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n.intValue();
    }

    private int count(String sql, Object... args) {
        Long n = appJdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n.intValue();
    }

    private UUID firstCategory(JdbcTemplate jdbc) {
        List<UUID> found = jdbc.queryForList("select category_id from catalog.product_categories order by category_id "
                + "limit 1", UUID.class);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, display_order, "
                + "active, created_at, updated_at, version) values (?,?,'MIG Cat',10,true,now(),now(),0)", id,
                "MC" + id.toString().substring(0, 8));
        return id;
    }

    private UUID seedProduct(JdbcTemplate jdbc, UUID cat, String code, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD',5,0.1,false,true,now(),now(),0)", id, code, cat, "Mig " + code,
                new java.math.BigDecimal(price));
        return id;
    }

    private void seedStoreProduct(JdbcTemplate jdbc, UUID store, UUID product, boolean available) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, sold_out_until, updated_at, "
                + "version) values (?,?,?,?,now(),0)", store, product, available, null);
    }
}
