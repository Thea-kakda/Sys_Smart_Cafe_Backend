package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.testsupport.IntegrationTestBase;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Database-level constraint tests for the V048 channel tables (Slice 3B.1, spec §6).
 * Every assertion drives the REAL Postgres schema over Testcontainers with direct SQL so
 * the guarantees are proven at the storage layer, not the JVM: the composite unique key,
 * the channel enum CHECK, non-negative display order, ISO day-of-week, the zero-length
 * window ban, every foreign key (store, product, schedule-&gt;channel with CASCADE), and
 * the persisted optimistic-lock version column.
 *
 * <p>The weekly-schedule OVERLAP rule is intentionally NOT a database constraint (it is
 * enforced in {@code ChannelManagementService}); these tests document that fact by
 * showing the DB permits overlapping rows, so the service is the single guard.
 */
@SpringBootTest
class ChannelConstraintIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    private UUID store;
    private UUID product;

    @BeforeEach
    void setUp() {
        store = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'CON Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", store,
                "CO" + UUID.randomUUID().toString().substring(0, 8));
        UUID cat = UUID.randomUUID();
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, display_order, "
                + "active, created_at, updated_at, version) values (?,?,'C',10,true,now(),now(),0)", cat,
                "CC" + UUID.randomUUID().toString().substring(0, 8));
        product = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?, 'Product',3,'USD',5,0.1,false,true,now(),now(),0)", product,
                "CONP-" + product.toString().substring(0, 8), cat);
    }

    private void channel(UUID id, String channel, int displayOrder, long version) {
        jdbc.update("insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, visible, "
                + "orderable, display_order, version, created_at, updated_at) values (?,?,?,?,'true','true',?,?,now(),now())",
                id, store, product, channel, displayOrder, version);
    }

    private void schedule(UUID channelId, int dow, LocalTime start, LocalTime end) {
        jdbc.update("insert into catalog.product_channel_schedules (schedule_id, product_channel_id, day_of_week, "
                + "start_local_time, end_local_time, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'true',now(),now(),0)", UUID.randomUUID(), channelId, dow, start, end);
    }

    @Test
    void uniqueStoreProductChannelIsEnforced() {
        channel(UUID.randomUUID(), "CUSTOMER_WEB", 0, 0);
        assertThrows(DataAccessException.class, () -> channel(UUID.randomUUID(), "CUSTOMER_WEB", 0, 0),
                "uk_product_channel rejects a second row for the same store+product+channel");
    }

    @Test
    void channelEnumRejectsUnknownValues() {
        assertThrows(DataAccessException.class, () -> channel(UUID.randomUUID(), "BOGUS", 0, 0),
                "ck_product_channel_enum rejects a non-declared channel");
    }

    @Test
    void displayOrderMustBeNonNegative() {
        assertThrows(DataAccessException.class, () -> channel(UUID.randomUUID(), "POS", -1, 0),
                "ck_product_channel_display_order rejects a negative order");
    }

    @Test
    void scheduleDayOfWeekMustBeIsoOneToSeven() {
        UUID ch = UUID.randomUUID();
        channel(ch, "POS", 0, 0);
        assertThrows(DataAccessException.class, () -> schedule(ch, 8, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        assertThrows(DataAccessException.class, () -> schedule(ch, 0, LocalTime.of(9, 0), LocalTime.of(17, 0)));
    }

    @Test
    void scheduleRejectsZeroLengthWindow() {
        UUID ch = UUID.randomUUID();
        channel(ch, "POS", 0, 0);
        assertThrows(DataAccessException.class, () -> schedule(ch, 3, LocalTime.of(9, 0), LocalTime.of(9, 0)),
                "ck_channel_schedule_span rejects start == end");
    }

    @Test
    void channelStoreForeignKeyIsEnforced() {
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, visible, "
                        + "orderable, display_order, version, created_at, updated_at) values (?,?,?,?,true,true,0,0,"
                        + "now(),now())", UUID.randomUUID(), UUID.randomUUID(), product, "POS"),
                "FK to organization.stores rejects an unknown store");
    }

    @Test
    void channelProductForeignKeyIsEnforced() {
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, visible, "
                        + "orderable, display_order, version, created_at, updated_at) values (?,?,?,?,true,true,0,0,"
                        + "now(),now())", UUID.randomUUID(), store, UUID.randomUUID(), "POS"),
                "FK to catalog.products rejects an unknown product");
    }

    @Test
    void scheduleForeignKeyToChannelIsEnforced() {
        assertThrows(DataAccessException.class, () -> schedule(UUID.randomUUID(), 3, LocalTime.of(9, 0),
                LocalTime.of(17, 0)), "FK to product_channels rejects an orphan schedule");
    }

    @Test
    void optimisticLockVersionIsPersisted() {
        UUID ch = UUID.randomUUID();
        channel(ch, "KIOSK", 5, 7);
        long v = jdbc.queryForObject("select version from catalog.product_channels where product_channel_id = ?",
                Long.class, ch);
        int order = jdbc.queryForObject("select display_order from catalog.product_channels where product_channel_id = ?",
                Integer.class, ch);
        assertEquals(7L, v, "the version column persists the optimistic-lock value");
        assertEquals(5, order);
    }

    @Test
    void deletingChannelCascadesSchedulesWithoutOrphans() {
        UUID ch = UUID.randomUUID();
        channel(ch, "CUSTOMER_WEB", 0, 0);
        schedule(ch, 1, LocalTime.of(9, 0), LocalTime.of(17, 0));
        schedule(ch, 2, LocalTime.of(9, 0), LocalTime.of(17, 0));
        assertEquals(2, countSchedules(ch));
        jdbc.update("delete from catalog.product_channels where product_channel_id = ?", ch);
        assertEquals(0, countSchedules(ch), "ON DELETE CASCADE removes the schedules; no orphan rows");
    }

    @Test
    void databaseAloneDoesNotEnforceOverlapThatIsServiceLevel() {
        // Documents the final overlap rule: the DB accepts two overlapping windows so the
        // ChannelManagementService.detectOverlap is the single guard (SCHEDULE_OVERLAP 409).
        UUID ch = UUID.randomUUID();
        channel(ch, "CUSTOMER_APP", 0, 0);
        schedule(ch, 1, LocalTime.of(9, 0), LocalTime.of(17, 0));
        schedule(ch, 1, LocalTime.of(12, 0), LocalTime.of(20, 0)); // overlaps the first
        assertEquals(2, countSchedules(ch), "two overlapping rows persist at the DB layer by design");
    }

    private int countSchedules(UUID channelId) {
        Integer n = jdbc.queryForObject("select count(*) from catalog.product_channel_schedules where "
                + "product_channel_id = ?", Integer.class, channelId);
        return n == null ? 0 : n;
    }

    /** Sanity: the enum values declared in the CHECK are exactly the six controlled channels. */
    @Test
    void allSixControlledChannelsAreAccepted() {
        for (String c : new String[] { "POS", "CUSTOMER_WEB", "CUSTOMER_APP", "QR_MENU", "KIOSK", "DELIVERY" }) {
            channel(UUID.randomUUID(), c, 0, 0);
        }
        assertTrue(true, "every declared channel value satisfies ck_product_channel_enum");
    }
}
