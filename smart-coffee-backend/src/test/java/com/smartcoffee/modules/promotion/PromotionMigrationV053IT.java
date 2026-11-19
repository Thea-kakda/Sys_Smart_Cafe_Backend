package com.smartcoffee.modules.promotion;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase B2 migration gate: V053 must apply cleanly to a database that already
 * holds V052-era Promotion data, must not rewrite it, and must add the new
 * columns plus the one-promotion-per-order uniqueness.
 */
@Testcontainers(disabledWithoutDocker = true)
class PromotionMigrationV053IT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void populatedV052UpgradesToV053WithoutRewritingData() throws Exception {
        Flyway v52 = configured().target(MigrationVersion.fromVersion("52")).load();
        v52.migrate();
        assertEquals("052", v52.info().current().getVersion().getVersion());

        UUID store = UUID.randomUUID();
        UUID promotion = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID redemption = UUID.randomUUID();
        try (Connection db = connect()) {
            insert(db, "insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                    + "default_currency, default_language, active, created_at, updated_at, version) "
                    + "values (?,?,?,'Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)",
                    store, "MIG" + shortId(), "Migration Branch");
            insert(db, "insert into promotion.promotions (promotion_id, promotion_code, promotion_name, "
                    + "discount_type, discount_value, minimum_order_amount, currency_code, starts_at, ends_at, "
                    + "status, usage_count, stackable, automatic, priority, version) "
                    + "values (?,?,?,'PERCENTAGE',10,0,'USD',now() - interval '1 hour',now() + interval '1 day',"
                    + "'ACTIVE',1,false,false,0,1)", promotion, "MIG" + shortId(), "Migrated promo");
            insert(db, "insert into ordering.orders (order_id, order_number, store_id, source, fulfillment_type, "
                    + "status, payment_status, currency_code, subtotal, discount_amount, tax_amount, delivery_fee, "
                    + "rounding_amount, total_amount, total_quantity, ordered_at, updated_at, version) "
                    + "values (?,?,?,'CUSTOMER_WEB','PICKUP','AWAITING_PAYMENT','PENDING','USD',10,1,0.9,0,0,9.9,1,"
                    + "now(),now(),0)", order, "MIG-" + shortId(), store);
            insert(db, "insert into promotion.promotion_redemptions (redemption_id, promotion_id, order_id, "
                    + "store_id, status, discount_amount, currency_code, reserved_at, version) "
                    + "values (?,?,?,?,'RESERVED',1,'USD',now(),0)", redemption, promotion, order, store);
        }

        Flyway latest = configured().load();
        latest.migrate();
        latest.validate();
        assertEquals("053", latest.info().current().getVersion().getVersion());

        try (Connection db = connect()) {
            assertEquals("RESERVED", text(db,
                    "select status from promotion.promotion_redemptions where redemption_id=?", redemption));
            assertNull(text(db,
                    "select eligible_subtotal from promotion.promotion_redemptions where redemption_id=?", redemption),
                    "no fake backfill is written for existing reservations");
            assertEquals(1, integer(db, "select usage_count from promotion.promotions where promotion_id=?",
                    promotion).intValue());
            assertTrue(columnExists(db, "ordering", "orders", "promotion_eligible_subtotal"));
            assertTrue(columnExists(db, "ordering", "orders", "promotion_discount_amount"));
            assertTrue(columnExists(db, "promotion", "promotion_redemptions", "promotion_code_snapshot"));
            assertTrue(columnExists(db, "promotion", "promotion_redemptions", "release_reason"));
            assertEquals(1, integer(db, "select count(*) from pg_indexes where schemaname='promotion' "
                    + "and indexname='ux_redemption_order'").intValue(), "one promotion per order is enforced");

            SQLException conflict = assertThrows(SQLException.class,
                    () -> insert(db, "insert into promotion.promotion_redemptions (redemption_id, promotion_id, "
                            + "order_id, store_id, status, discount_amount, currency_code, reserved_at, version) "
                            + "values (?,?,?,?,'RESERVED',1,'USD',now(),0)", UUID.randomUUID(), promotion, order,
                            store));
            assertEquals("23505", conflict.getSQLState(), "the unique index rejects a second promotion per order");
        }
    }

    // ------------------------------------------------------------- helpers

    private static FluentConfiguration configured() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void insert(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof UUID uuid)
                    statement.setObject(i + 1, uuid);
                else if (arg instanceof Integer number)
                    statement.setInt(i + 1, number);
                else
                    statement.setObject(i + 1, arg);
            }
            statement.executeUpdate();
        }
    }

    private static String text(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static Integer integer(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static boolean columnExists(Connection db, String schema, String table, String column)
            throws SQLException {
        try (PreparedStatement statement = db.prepareStatement("select count(*) from information_schema.columns "
                + "where table_schema=? and table_name=? and column_name=?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID uuid)
                statement.setObject(i + 1, uuid);
            else if (args[i] instanceof Timestamp timestamp)
                statement.setTimestamp(i + 1, timestamp);
            else
                statement.setObject(i + 1, args[i]);
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
