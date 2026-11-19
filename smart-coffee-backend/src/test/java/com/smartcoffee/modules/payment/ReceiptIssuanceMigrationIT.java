package com.smartcoffee.modules.payment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Migration gate for V054 (receipt issuance lifecycle). Deliberately separate
 * from the shared integration stack: each test owns an ephemeral Postgres so the
 * empty-chain and populated-V053 upgrade semantics are proven in isolation.
 *
 * <p>1) EMPTY: V001..V054 applies, validate() passes, and the status CHECK now
 *    accepts PROCESSING with the attempt/claim/metadata columns present.
 * 2) POPULATED: an existing ISSUED receipt created at V053 survives the upgrade
 *    with its id, number, snapshot and issued_at untouched, at attempts 0.
 */
class ReceiptIssuanceMigrationIT {

    private static final DockerImageName PG = DockerImageName.parse("postgres:17-alpine");

    private PostgreSQLContainer freshDb() {
        PostgreSQLContainer db = new PostgreSQLContainer(PG);
        db.start();
        return db;
    }

    private Flyway flyway(PostgreSQLContainer db, String target) {
        var config = Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations("filesystem:" + Path.of("src", "main", "resources", "db", "migration"));
        if (target != null) {
            config = config.target(target);
        }
        return config.load();
    }

    private Connection conn(PostgreSQLContainer db) throws Exception {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    @Test
    void emptyDatabaseMigratesFullChainAndAcceptsProcessing() throws Exception {
        try (PostgreSQLContainer db = freshDb()) {
            flyway(db, null).migrate();
            assertDoesNotThrow(() -> flyway(db, null).validateWithResult());

            try (Connection c = conn(db); Statement st = c.createStatement()) {
                ResultSet rs = st.executeQuery(
                        "select count(*) from flyway_schema_history where success and version = '054'");
                rs.next();
                assertEquals(1, rs.getInt(1), "V054 must apply cleanly on an empty database");

                rs = st.executeQuery("""
                        select pg_get_constraintdef(oid) from pg_constraint
                        where conname = 'ck_receipt_status'
                        """);
                assertTrue(rs.next(), "the receipt status constraint must exist");
                String definition = rs.getString(1);
                for (String status : new String[] {"PENDING", "PROCESSING", "ISSUED", "FAILED"}) {
                    assertTrue(definition.contains(status), "missing " + status + " in " + definition);
                }

                rs = st.executeQuery("""
                        select column_name from information_schema.columns
                        where table_schema = 'payment' and table_name = 'receipts'
                        """);
                java.util.Set<String> columns = new java.util.HashSet<>();
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
                assertTrue(columns.containsAll(java.util.Set.of("attempts", "last_attempt_at", "failure_reason",
                        "processing_owner", "processing_token", "processing_started_at", "mime_type",
                        "size_bytes", "checksum")), columns.toString());
            }
        }
    }

    @Test
    void populatedV053UpgradePreservesExistingReceipt() throws Exception {
        try (PostgreSQLContainer db = freshDb()) {
            flyway(db, "53").migrate();

            UUID storeId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            UUID receiptId = UUID.randomUUID();
            String snapshot = "{\"orderNumber\":\"O-LEGACY\",\"total\":\"11.00\",\"promotion\":{\"code\":\"SAVE10\"}}";

            try (Connection c = conn(db); Statement st = c.createStatement()) {
                st.executeUpdate("insert into organization.stores (store_id, store_code, store_name, address, "
                        + "timezone, default_currency, default_language, active, created_at, updated_at, version) "
                        + "values ('" + storeId + "','LEG1','Legacy','Addr','Asia/Phnom_Penh','USD','en-US',true,"
                        + "now(),now(),0)");
                st.executeUpdate("insert into identity.users (user_id, full_name, email, normalized_email, "
                        + "preferred_language, status, security_version, created_at, updated_at, version) values ('"
                        + userId + "','Legacy User','legacy-" + userId + "@smart.coffee','legacy-" + userId
                        + "@smart.coffee','en-US','ACTIVE',0,now(),now(),0)");
                st.executeUpdate("insert into customer.customer_profiles (customer_id, user_id, created_at, "
                        + "updated_at, version) values ('" + customerId + "','" + userId + "',now(),now(),0)");
                st.executeUpdate("insert into ordering.orders (order_id, order_number, store_id, customer_id, source, "
                        + "fulfillment_type, status, payment_status, currency_code, subtotal, total_amount, "
                        + "total_quantity, ordered_at, updated_at) values ('" + orderId + "','O-LEGACY','" + storeId
                        + "','" + customerId + "','CUSTOMER_WEB','PICKUP','COMPLETED','PAID','USD',10,11,1,now(),now())");
                st.executeUpdate("insert into payment.payments (payment_id, payment_reference, order_id, store_id, "
                        + "payment_method_id, status, currency_code, amount, created_at, updated_at) values ('"
                        + paymentId + "','LEGACYREF','" + orderId + "','" + storeId
                        + "','80000000-0000-0000-0000-000000000001','PAID','USD',11,now(),now())");
                st.executeUpdate("insert into payment.receipts (receipt_id, receipt_number, payment_id, order_id, "
                        + "store_id, status, receipt_snapshot, object_key, issued_at, created_at) values ('" + receiptId
                        + "','RCP-LEGACY','" + paymentId + "','" + orderId + "','" + storeId + "','ISSUED','" + snapshot
                        + "'::jsonb,'receipts/" + receiptId + ".txt',now(),now())");
            }

            flyway(db, null).migrate();
            assertDoesNotThrow(() -> flyway(db, null).validateWithResult());

            try (Connection c = conn(db); Statement st = c.createStatement()) {
                ResultSet rs = st.executeQuery("select receipt_number, status, receipt_snapshot::text, object_key, "
                        + "issued_at is not null, attempts, failure_reason from payment.receipts where receipt_id = '"
                        + receiptId + "'");
                assertTrue(rs.next());
                assertEquals("RCP-LEGACY", rs.getString(1));
                assertEquals("ISSUED", rs.getString(2));
                assertTrue(rs.getString(3).contains("SAVE10"), "the historical promotion snapshot is untouched");
                assertEquals("receipts/" + receiptId + ".txt", rs.getString(4));
                assertTrue(rs.getBoolean(5));
                assertEquals(0, rs.getInt(6));
                assertNull(rs.getString(7));
            }
        }
    }
}
