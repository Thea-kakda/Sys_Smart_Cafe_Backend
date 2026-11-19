package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * Formal V049 migration verification (Slice 4A Round 2, spec §2/§3/§10) on BOTH
 * a fresh and a genuinely populated database — never inferred from a fresh boot.
 *
 * <p>A) FRESH: the application Testcontainers database has already been migrated
 * V001..V049 with {@code validate-on-migrate: true}, so a programmatic
 * {@link Flyway#validate()} re-confirms every V001..V048 checksum is unchanged,
 * and the object/constraint/index assertions plus a direct check of the V003
 * seed relationships prove the backfill ran against real production seed data
 * (MILK/SUGAR groups on ICED_LATTE).
 *
 * <p>B) POPULATED: a throwaway Postgres container is migrated to V048, seeded
 * with a representative pre-existing product/group/option/attachment set — a
 * SINGLE group with two ACTIVE and one INACTIVE option — then upgraded by a full
 * migrate. The test asserts the additive backfill copies the master limits,
 * invents NO default, lists only the ACTIVE options, preserves required and
 * display order and every product id/price, seeds an independent relationship
 * version, and is idempotent.
 */
@SpringBootTest
class CustomizationOverrideMigrationIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate appJdbc;
    @Autowired
    DataSource appDataSource;

    // =============================================================== A. FRESH DB

    @Test
    void freshDatabaseReachesV049WithValidChecksumsAndExpectedObjects() {
        Flyway flyway = Flyway.configure().dataSource(appDataSource).locations("classpath:db/migration").load();
        assertDoesNotThrow(flyway::validate, "V001..V048 checksums unchanged and V049 valid on the fresh boot");

        // V049 (Round 2 customization overrides) is applied and validated here; the
        // schema head is now V053 (V051 Slice 5A production orders, V052 SUPER_ADMIN
        // RBAC hardening, V053 Phase B2 promotion checkout reservation), tracked by
        // this pin so a further migration fails loudly if added unnoticed.
        assertEquals("053", appJdbc.queryForObject(
                "select max(version) from flyway_schema_history where success and version is not null", String.class));
        assertEquals(1, count("select count(*) from flyway_schema_history where version = '049'"),
                "V049 applied exactly once");

        // New table + added columns exist.
        assertNotNull(appJdbc.queryForObject("select to_regclass('catalog.product_customization_options')::text",
                String.class));
        assertEquals(1, count("select count(*) from information_schema.columns where table_schema='catalog' and "
                + "table_name='product_customization_groups' and column_name='version'"), "relationship version column");
        assertEquals(1, count("select count(*) from information_schema.columns where table_schema='catalog' and "
                + "table_name='product_customization_groups' and column_name='default_option_id'"), "default option column");

        // Constraints and indexes from V049.
        assertEquals(1, count("select count(*) from pg_constraint where conname='ck_pcg_min_le_max'"));
        assertEquals(1, count("select count(*) from pg_constraint where conname='uk_product_customization_option'"));
        assertEquals(1, count("select count(*) from pg_constraint where conname='fk_pcg_option_attachment'"));
        assertEquals(1, count("select count(*) from pg_constraint where conname='fk_pcg_default_option'"));
        assertEquals(1, count("select count(*) from pg_indexes where indexname='ix_pcg_option_lookup'"));

        // The V003 seed relationship (ICED_LATTE -> MILK and SUGAR) is backfilled:
        // every pre-existing attachment has a relationship version, no invented
        // default, and an allow-list covering its group's ACTIVE options only.
        UUID icedLatte = UUID.fromString("30000000-0000-0000-0000-000000000001");
        for (UUID groupId : List.of(milkGroup(), sugarGroup())) {
            assertEquals(0, count("select count(*) from catalog.product_customization_groups pcg join "
                    + "catalog.customization_groups cg on cg.group_id=pcg.group_id where pcg.product_id=? and "
                    + "pcg.group_id=? and (pcg.minimum_selections is distinct from cg.minimum_selections or "
                    + "pcg.maximum_selections is distinct from cg.maximum_selections)", icedLatte, groupId),
                    "master limits copied exactly");
            assertEquals(0, count("select count(*) from catalog.product_customization_groups where product_id=? and "
                    + "group_id=? and default_option_id is not null", icedLatte, groupId), "no default invented on the seed");
            assertEquals(count("select count(*) from catalog.customization_options where group_id=? and active", groupId),
                    count("select count(*) from catalog.product_customization_options where product_id=? and group_id=?",
                            icedLatte, groupId),
                    "every ACTIVE option backfilled into the allow-list");
            assertTrue(count("select count(*) from catalog.product_customization_groups where product_id=? and "
                    + "group_id=? and version=0 and active", icedLatte, groupId) == 1, "relationship version seeded");
        }
    }

    // ==================================================== B. POPULATED V048 -> V049

    @Test
    void populatedV048DatabaseUpgradesToV049WithBackfillAndNoDataLoss() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(),
                    pg.getPassword());

            // 1. Apply only through V048.
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("48").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertEquals(1, count(jdbc, "select count(*) from flyway_schema_history where version = '048'"));
            assertEquals(0, count(jdbc, "select count(*) from flyway_schema_history where version = '049'"),
                    "V049 not yet applied");

            // 2. Seed representative PRE-EXISTING state: a SINGLE group capped at 1
            //    with two active + one inactive option, attached to a product.
            UUID cat = firstCategory(jdbc);
            UUID product = seedProduct(jdbc, cat, "MIG-P", "4.00");
            UUID group = seedGroup(jdbc, "SINGLE", 1, 1);
            UUID optA = seedOption(jdbc, group, "A_ACTIVE", true);
            UUID optB = seedOption(jdbc, group, "B_ACTIVE", true);
            seedOption(jdbc, group, "C_INACTIVE", false);
            jdbc.update("insert into catalog.product_customization_groups (product_id, group_id, required, "
                    + "display_order) values (?,?,true,7)", product, group);

            List<UUID> idsBefore = jdbc.queryForList("select product_id from catalog.products order by product_id",
                    UUID.class);
            BigDecimal priceBefore = jdbc.queryForObject(
                    "select base_price from catalog.products where product_code='MIG-P'", BigDecimal.class);

            // 3. Apply V049 (full migrate now runs 49 only).
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            assertEquals(1, count(jdbc, "select count(*) from flyway_schema_history where version = '049'"),
                    "V049 applied to the populated DB exactly once");

            // 4a. No data loss: product ids and price untouched; attachment preserved.
            assertEquals(idsBefore, jdbc.queryForList("select product_id from catalog.products order by product_id",
                    UUID.class), "existing product ids unchanged");
            assertEquals(0, priceBefore.compareTo(jdbc.queryForObject(
                    "select base_price from catalog.products where product_code='MIG-P'", BigDecimal.class)),
                    "existing prices unchanged by the migration");
            assertEquals(1, count(jdbc, "select count(*) from catalog.product_customization_groups where "
                    + "product_id=? and group_id=? and required and display_order=7", product, group),
                    "required + display order preserved");

            // 4b. Backfill copied the master limits and did NOT invent a default.
            Map<String, Object> row = jdbc.queryForMap("select minimum_selections, maximum_selections, default_option_id, "
                    + "version, active from catalog.product_customization_groups where product_id=? and group_id=?",
                    product, group);
            assertEquals(1, ((Number) row.get("minimum_selections")).intValue(), "min copied from master");
            assertEquals(1, ((Number) row.get("maximum_selections")).intValue(), "max copied from master");
            assertNull(row.get("default_option_id"), "no default option invented");
            assertEquals(0, ((Number) row.get("version")).intValue(), "independent relationship version starts at 0");
            assertEquals(Boolean.TRUE, row.get("active"), "attachment is active");

            // 4c. Allow-list covers exactly the ACTIVE options, allowed, no price override.
            assertEquals(2, count(jdbc, "select count(*) from catalog.product_customization_options where "
                    + "product_id=? and group_id=?", product, group), "only the two active options are backfilled");
            assertEquals(2, count(jdbc, "select count(*) from catalog.product_customization_options where "
                    + "product_id=? and group_id=? and allowed and price_override is null", product, group));
            assertEquals(1, count(jdbc, "select count(*) from catalog.product_customization_options where "
                    + "product_id=? and option_id=?", product, optA));
            assertEquals(1, count(jdbc, "select count(*) from catalog.product_customization_options where "
                    + "product_id=? and option_id=?", product, optB));

            // 4d. Idempotency: re-running the option backfill adds nothing (ON CONFLICT DO NOTHING).
            long rowsBefore = count(jdbc, "select count(*) from catalog.product_customization_options");
            jdbc.update("insert into catalog.product_customization_options (product_customization_option_id, "
                    + "product_id, group_id, option_id, allowed, display_order, version, active) "
                    + "select gen_random_uuid(), pcg.product_id, pcg.group_id, co.option_id, true, co.display_order, "
                    + "0, true from catalog.product_customization_groups pcg join catalog.customization_options co "
                    + "on co.group_id=pcg.group_id and co.active=true on conflict (product_id, group_id, option_id) "
                    + "do nothing");
            assertEquals(rowsBefore, count(jdbc, "select count(*) from catalog.product_customization_options"),
                    "backfill is idempotent");
        }
    }

    // ------------------------------------------------------------------ helpers

    private UUID milkGroup() {
        return appJdbc.queryForObject("select group_id from catalog.customization_groups where group_code='MILK'",
                UUID.class);
    }

    private UUID sugarGroup() {
        return appJdbc.queryForObject("select group_id from catalog.customization_groups where group_code='SUGAR'",
                UUID.class);
    }

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
                new BigDecimal(price));
        return id;
    }

    private UUID seedGroup(JdbcTemplate jdbc, String selectionType, int min, int max) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_groups (group_id, group_code, group_name, selection_type, "
                + "minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version) "
                + "values (?,?,'MIG Group',?,?,?,0,true,now(),now(),0)", id,
                "MIGG" + id.toString().substring(0, 8), selectionType, min, max);
        return id;
    }

    private UUID seedOption(JdbcTemplate jdbc, UUID group, String code, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_options (option_id, group_id, option_code, option_name, "
                + "additional_price, display_order, active) values (?,?,?,?,'0.0000',10,?)", id, group, code,
                "Opt " + code, active);
        return id;
    }
}
