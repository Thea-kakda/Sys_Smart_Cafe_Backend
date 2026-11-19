package com.smartcoffee.modules.recipe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Slice 2B.1 migration-gate verification for V044 (product fallback pointer +
 * backfill + drop of uk_recipe_one_active_per_product) against REAL fresh
 * databases — deliberately NOT the shared {@code IntegrationTestBase} stack:
 * each test owns an ephemeral Postgres so the empty-vs-populated semantics and
 * the preflight guard are proven in isolation.
 *
 * <p>1) EMPTY database: full V001..V044 chain applies and {@code validate()}
 * passes; schema objects land as specified.
 * 2) POPULATED database: V001..V043 apply, legacy single-ACTIVE products are
 * captured, V044 then backfills their pointer to the SAME version id (checkout
 * parity), drops the selector index, and every historical checksum stays valid.
 * 3) PREFLIGHT: a variant pointer referencing another product's version fails
 * the migration before anything is altered.
 */
class RecipeAssignmentMigrationIT {

    private static final DockerImageName PG = DockerImageName.parse("postgres:17-alpine");

    private PostgreSQLContainer freshDb() {
        PostgreSQLContainer db = new PostgreSQLContainer(PG);
        db.start();
        return db;
    }

    private Flyway flyway(PostgreSQLContainer db, String locations) {
        return Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations(locations)
                .load();
    }

    private java.sql.Connection conn(PostgreSQLContainer db) throws Exception {
        return java.sql.DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    /** Copies V001..V043 (everything BEFORE 2B.1) into a temp dir, returns its filesystem: URI. */
    private String legacyLocations() throws Exception {
        Path src = Path.of("src", "main", "resources", "db", "migration");
        assertTrue(Files.isDirectory(src), "expected migration dir at " + src.toAbsolutePath());
        Path tmp = Files.createTempDirectory("flyway-pre-v044");
        try (var stream = Files.list(src)) {
            stream.filter(p -> p.getFileName().toString().matches("V0\\d{2}__.*\\.sql"))
                    .filter(p -> Integer.parseInt(p.getFileName().toString().substring(1, 4)) < 44)
                    .forEach(p -> {
                        try {
                            Files.copy(p, tmp.resolve(p.getFileName()));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return "filesystem:" + tmp;
    }

    private String fullLocations() {
        return "filesystem:" + Path.of("src", "main", "resources", "db", "migration");
    }

    // ------------------------------------------------------------------ tests

    @Test
    void emptyDatabaseMigratesFullChainAndValidates() throws Exception {
        try (PostgreSQLContainer db = freshDb()) {
            flyway(db, fullLocations()).migrate();
            // Every migration through V044 applied on a greenfield database.
            try (var c = conn(db); var st = c.createStatement()) {
                var rs = st.executeQuery(
                        "select count(*) from flyway_schema_history where success and version = '044'");
                rs.next();
                assertEquals(1, rs.getInt(1), "V044 must apply cleanly on an empty database");
                rs = st.executeQuery("select count(*) from flyway_schema_history where success=false");
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            // validate() passing proves no applied migration (V039..V043 included) was edited.
            assertDoesNotThrow(() -> flyway(db, fullLocations()).validateWithResult());
            // Schema objects: pointer column + index exist, selector index is gone.
            try (var c = conn(db); var st = c.createStatement()) {
                var rs = st.executeQuery(
                        "select count(*) from information_schema.columns where table_schema='catalog' "
                                + "and table_name='products' and column_name='active_recipe_version_id'");
                rs.next();
                assertEquals(1, rs.getInt(1));
                rs = st.executeQuery("select count(*) from pg_indexes where indexname='uk_recipe_one_active_per_product'");
                rs.next();
                assertEquals(0, rs.getInt(1), "V044 must drop the one-ACTIVE selector index");
                rs = st.executeQuery("select count(*) from pg_indexes where indexname='ix_product_active_recipe'");
                rs.next();
                assertEquals(1, rs.getInt(1));
                rs = st.executeQuery("select count(*) from pg_constraint where conname='fk_product_active_recipe'");
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void populatedDatabaseBackfillsPointersWithoutChangingIdsOrChecksums() throws Exception {
        try (PostgreSQLContainer db = freshDb()) {
            // Phase 1: the schema exactly as Slice 2B shipped it.
            flyway(db, legacyLocations()).migrate();

            // Capture the pre-V044 contract: products with exactly one legacy ACTIVE.
            UUID versionId;
            int expectedBackfills;
            try (var c = conn(db); var st = c.createStatement()) {
                st.execute("create table pre_v044_expect as "
                        + "select p.product_id, (array_agg(rv.recipe_version_id order by rv.recipe_version_id))[1] "
                        + "as expected_version "
                        + "from catalog.products p join recipe.recipe_versions rv on rv.product_id = p.product_id "
                        + "where rv.status = 'ACTIVE' group by p.product_id having count(*) = 1");
                var rs = st.executeQuery("select count(*) from pre_v044_expect");
                rs.next();
                expectedBackfills = rs.getInt(1);
                rs = st.executeQuery(
                        "select recipe_version_id from recipe.recipe_versions where status='ACTIVE' limit 1");
                rs.next();
                versionId = rs.getObject(1, UUID.class);
            }
            assertTrue(expectedBackfills >= 1, "V001..V043 seed at least one legacy ACTIVE product recipe");

            // Phase 2: forward-only correction.
            flyway(db, fullLocations()).migrate();

            try (var c = conn(db); var st = c.createStatement()) {
                // Every captured product now points AT ITS OWN PRIOR VERSION — same id.
                var rs = st.executeQuery("select count(*) from pre_v044_expect e "
                        + "join catalog.products p on p.product_id = e.product_id "
                        + "where p.active_recipe_version_id is distinct from e.expected_version");
                rs.next();
                assertEquals(0, rs.getInt(1), "backfill must reproduce the prior ACTIVE selection exactly");

                // No pointer may reference a foreign-product or non-ACTIVE version.
                rs = st.executeQuery("select count(*) from catalog.products p "
                        + "join recipe.recipe_versions rv on rv.recipe_version_id = p.active_recipe_version_id "
                        + "where rv.product_id <> p.product_id or rv.status <> 'ACTIVE'");
                rs.next();
                assertEquals(0, rs.getInt(1));

                // The seeded ACTIVE id itself is untouched (FKs/snapshots keep resolving).
                try (var ps = c.prepareStatement(
                        "select status from recipe.recipe_versions where recipe_version_id = ?")) {
                    ps.setObject(1, versionId);
                    var r2 = ps.executeQuery();
                    assertTrue(r2.next());
                    assertEquals("ACTIVE", r2.getString(1), "migration must not mutate existing versions");
                }

                // Contradiction resolved: a second ACTIVE version for the SAME product now inserts.
                rs = st.executeQuery("select product_id from recipe.recipe_versions where status='ACTIVE' limit 1");
                rs.next();
                UUID someProduct = rs.getObject(1, UUID.class);
                try (var ps = c.prepareStatement("insert into recipe.recipe_versions (recipe_version_id, "
                        + "product_id, version_number, status, effective_from, created_at, updated_at, "
                        + "yield_quantity, version) values (?, ?, 99, 'ACTIVE', now(), now(), now(), 1, 0)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, someProduct);
                    assertEquals(1, ps.executeUpdate(), "two ACTIVE versions for one product must now coexist");
                }
            }

            // Checksums of the applied chain still validate (nothing edited post-hoc).
            assertDoesNotThrow(() -> flyway(db, fullLocations()).validateWithResult());
        } catch (java.sql.SQLException e) {
            fail("database verification failed: " + e.getMessage(), e);
        }
    }

    @Test
    void preflightRejectsVariantPointerForeignToItsProduct() throws Exception {
        try (PostgreSQLContainer db = freshDb()) {
            flyway(db, legacyLocations()).migrate();
            // Corrupt a variant pointer ACROSS products the way a bad hotfix could.
            try (var c = conn(db); var st = c.createStatement()) {
                st.execute("insert into catalog.product_categories (category_id, category_code, category_name) "
                        + "values ('0a0a0a0a-0000-0000-0000-0000000000aa', 'PFX-C', 'Preflight')");
                st.execute("insert into catalog.products (product_id, product_code, category_id, product_name, "
                        + "base_price, currency_code, created_at, updated_at, version) values "
                        + "('0a0a0a0a-0000-0000-0000-0000000000a1','PFX-P1','0a0a0a0a-0000-0000-0000-0000000000aa',"
                        + "'P1',1,'USD',now(),now(),0)");
                st.execute("insert into catalog.products (product_id, product_code, category_id, product_name, "
                        + "base_price, currency_code, created_at, updated_at, version) values "
                        + "('0a0a0a0a-0000-0000-0000-0000000000a2','PFX-P2','0a0a0a0a-0000-0000-0000-0000000000aa',"
                        + "'P2',1,'USD',now(),now(),0)");
                st.execute("insert into recipe.recipe_versions (recipe_version_id, product_id, version_number, "
                        + "status, effective_from, created_at, updated_at, yield_quantity, version) values "
                        + "('0a0a0a0a-0000-0000-0000-0000000000b1','0a0a0a0a-0000-0000-0000-0000000000a2',1,"
                        + "'ACTIVE',now(),now(),now(),1,0)");
                st.execute("insert into catalog.product_variants (variant_id, product_id, variant_code, "
                        + "variant_name, price, currency_code, is_default, active, display_order, created_at, "
                        + "updated_at, version, active_recipe_version_id) values "
                        + "('0a0a0a0a-0000-0000-0000-0000000000c1','0a0a0a0a-0000-0000-0000-0000000000a1',"
                        + "'PFX-V','V',1,'USD',false,true,0,now(),now(),0,"
                        + "'0a0a0a0a-0000-0000-0000-0000000000b1')");
            }
            Flyway v044 = flyway(db, fullLocations());
            var ex = assertThrows(org.flywaydb.core.internal.exception.FlywayMigrateException.class,
                    v044::migrate);
            assertTrue(rootMessage(ex).contains("RECIPE_ASSIGNMENT_PREFLIGHT_FAILED"),
                    "preflight must fail with its documented code, got: " + rootMessage(ex));
            assertTrue(rootMessage(ex).contains("FOREIGN-PRODUCT"),
                    "preflight message must name the violation class, got: " + rootMessage(ex));
            // And it failed BEFORE adding the column — nothing half-applied.
            try (var c = conn(db); var st = c.createStatement()) {
                var rs = st.executeQuery("select count(*) from information_schema.columns where "
                        + "table_schema='catalog' and table_name='products' "
                        + "and column_name='active_recipe_version_id'");
                rs.next();
                assertEquals(0, rs.getInt(1), "failed preflight must leave the schema untouched");
            }
        } catch (java.sql.SQLException e) {
            fail("database verification failed: " + e.getMessage(), e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) {
            r = r.getCause();
        }
        return r.getMessage() == null ? "" : r.getMessage();
    }
}
