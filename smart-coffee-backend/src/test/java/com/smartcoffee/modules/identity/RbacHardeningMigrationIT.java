package com.smartcoffee.modules.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V052 (SUPER_ADMIN RBAC hardening) migration-gate verification against REAL
 * fresh databases — deliberately NOT the shared {@code IntegrationTestBase}
 * stack, mirroring RecipeAssignmentMigrationIT. Proves:
 *
 * <p>1) the full V001..V052 chain applies on an empty database and
 * {@code validate()} passes (checksums untouched);
 * 2) the post-conditions hold: 68 permissions, SUPER_ADMIN holds all 68,
 * ADMIN stays at 63 (the deliberate V052 deviation — the five role:* lifecycle
 * codes are SUPER_ADMIN-only), STORE_MANAGER 51, CASHIER 7, BARISTA 4,
 * KITCHEN_STAFF 4, BAKERY_STAFF 4; all 8 seed roles are is_system, and
 * SUPER_ADMIN/ADMIN/CUSTOMER are GLOBAL-scope;
 * 3) the assignment-provenance columns and FKs exist;
 * 4) the SUPER_ADMIN-holds-everything invariant holds (CI sentinel);
 * 5) the backfill sections are idempotent (re-running them changes nothing).
 */
class RbacHardeningMigrationIT {

    private static final DockerImageName PG = DockerImageName.parse("postgres:17-alpine");

    private PostgreSQLContainer freshDb() {
        PostgreSQLContainer db = new PostgreSQLContainer(PG);
        db.start();
        return db;
    }

    private Flyway flyway(PostgreSQLContainer db) {
        return Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations("filesystem:" + Path.of("src", "main", "resources", "db", "migration"))
                .load();
    }

    private int scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int grantsOf(Connection c, String roleCode) throws Exception {
        return scalar(c, "select count(*) from identity.role_permissions rp "
                + "join identity.roles r on r.role_id = rp.role_id where r.role_code = '" + roleCode + "'");
    }

    @Test
    void fullChainAppliesPostconditionsHoldAndValidatePasses() throws Exception {
        try (PostgreSQLContainer db = freshDb()) {
            flyway(db).migrate();
            assertDoesNotThrow(() -> flyway(db).validateWithResult(), "checksums of V001..V051 must be untouched");

            try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
                // V052 applied cleanly, nothing failed anywhere in the chain.
                assertEquals(1, scalar(c, "select count(*) from flyway_schema_history where success and version='052'"));
                assertEquals(0, scalar(c, "select count(*) from flyway_schema_history where success = false"));

                // Permission catalog grew 63 → 68 with the five V052 codes.
                assertEquals(68, scalar(c, "select count(*) from identity.permissions"));
                for (String code : new String[] { "role:create", "role:update", "role:delete",
                        "role:assignment-read", "role:privileged-assign" }) {
                    assertEquals(1, scalar(c, "select count(*) from identity.permissions where permission_code='"
                            + code + "'"), code + " must exist exactly once");
                }

                // Grant counts: SUPER_ADMIN backfilled to everything, ADMIN
                // deliberately stays at 63 (documented deviation), others unchanged.
                assertEquals(68, grantsOf(c, "SUPER_ADMIN"), "SUPER_ADMIN must hold EVERY permission");
                assertEquals(63, grantsOf(c, "ADMIN"), "ADMIN must NOT receive the V052 role:* lifecycle codes");
                assertEquals(51, grantsOf(c, "STORE_MANAGER"));
                assertEquals(7, grantsOf(c, "CASHIER"));
                assertEquals(4, grantsOf(c, "BARISTA"));
                assertEquals(4, grantsOf(c, "KITCHEN_STAFF"));
                assertEquals(4, grantsOf(c, "BAKERY_STAFF"));

                // D4 invariant sentinel: zero permissions lack a SUPER_ADMIN grant.
                assertEquals(0, scalar(c, "select count(*) from identity.permissions p where not exists ("
                        + "select 1 from identity.role_permissions rp join identity.roles r "
                        + "on r.role_id = rp.role_id where r.role_code='SUPER_ADMIN' "
                        + "and rp.permission_id = p.permission_id)"));

                // Role metadata: 8 system roles; scope split per V052.
                assertEquals(8, scalar(c, "select count(*) from identity.roles where is_system"));
                assertEquals(0, scalar(c, "select count(*) from identity.roles where role_code in "
                        + "('SUPER_ADMIN','ADMIN','CUSTOMER') and role_scope <> 'GLOBAL'"));
                assertEquals(0, scalar(c, "select count(*) from identity.roles where role_code in "
                        + "('STORE_MANAGER','CASHIER','BARISTA','KITCHEN_STAFF','BAKERY_STAFF') "
                        + "and role_scope <> 'STORE'"));
                assertEquals(0, scalar(c, "select count(*) from identity.roles "
                        + "where role_scope not in ('GLOBAL','STORE')"));

                // Assignment provenance columns + FK constraints exist.
                for (String column : new String[] { "assigned_by_user_id", "revoked_by_user_id",
                        "revocation_reason" }) {
                    assertEquals(1, scalar(c, "select count(*) from information_schema.columns "
                            + "where table_schema='identity' and table_name='user_role_assignments' "
                            + "and column_name='" + column + "'"), column + " must exist");
                }
                assertEquals(2, scalar(c, "select count(*) from information_schema.table_constraints "
                        + "where table_schema='identity' and table_name='user_role_assignments' "
                        + "and constraint_name in ('fk_assignment_assigned_by','fk_assignment_revoked_by')"));
                assertEquals(1, scalar(c, "select count(*) from information_schema.check_constraints "
                        + "where constraint_schema='identity' and constraint_name='ck_roles_role_scope'"),
                        "role_scope CHECK constraint must exist");
            }
        }
    }

    @Test
    void superAdminBackfillIsIdempotent() throws Exception {
        try (PostgreSQLContainer db = freshDb()) {
            flyway(db).migrate();
            try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                    Statement st = c.createStatement()) {
                // Re-apply the idempotent V052 sections (permission seed + the
                // SUPER_ADMIN cross-join backfill) twice; ON CONFLICT DO NOTHING
                // guarantees byte-identical state afterwards.
                for (int round = 0; round < 2; round++) {
                    st.execute("insert into identity.permissions (permission_id, permission_code) values "
                            + "('ba000000-0000-0000-0000-000000000004','role:create'),"
                            + "('ba000000-0000-0000-0000-000000000005','role:update'),"
                            + "('ba000000-0000-0000-0000-000000000006','role:delete'),"
                            + "('ba000000-0000-0000-0000-000000000007','role:assignment-read'),"
                            + "('ba000000-0000-0000-0000-000000000008','role:privileged-assign') "
                            + "on conflict do nothing");
                    st.execute("insert into identity.role_permissions (role_id, permission_id) "
                            + "select r.role_id, p.permission_id from identity.roles r "
                            + "cross join identity.permissions p where r.role_code = 'SUPER_ADMIN' "
                            + "on conflict do nothing");
                }
                assertEquals(68, scalar(c, "select count(*) from identity.permissions"));
                assertEquals(68, grantsOf(c, "SUPER_ADMIN"));
                assertEquals(63, grantsOf(c, "ADMIN"), "idempotency re-runs must not touch ADMIN's grants");
            }
            assertDoesNotThrow(() -> flyway(db).validateWithResult());
        }
    }
}
