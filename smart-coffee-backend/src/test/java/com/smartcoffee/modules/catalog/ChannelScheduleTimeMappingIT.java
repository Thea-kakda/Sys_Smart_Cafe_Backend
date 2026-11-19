package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smartcoffee.modules.catalog.domain.ProductChannelSchedule;
import com.smartcoffee.modules.catalog.persistence.ProductChannelScheduleRepository;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Slice 3B.2 (§3) — canonical {@code TIME WITHOUT TIME ZONE} &harr; {@link LocalTime} persistence
 * contract. This is the PERMANENT regression guard for the asymmetric mapping defect found in
 * Slice 3B.1: the JVM default timezone must NOT change how a stored wall-clock schedule time is
 * interpreted. The full matrix is exercised:
 *
 * <ul>
 *   <li><b>A</b> Raw SQL write &rarr; JPA read: a {@code TIME '09:00:00'} row must load as
 *       {@code LocalTime.of(9,0)} — never shifted by the connection timezone.</li>
 *   <li><b>B</b> JPA write &rarr; raw SQL read: saving {@code LocalTime.of(9,0)} must store the
 *       literal {@code 09:00:00} in PostgreSQL, not a fake UTC-shifted value.</li>
 *   <li><b>C</b> JPA write &rarr; JPA reload: round-trips to exactly the supplied local times.</li>
 * </ul>
 *
 * <p>The whole class is executed once per {@code -Duser.timezone} (UTC, Asia/Phnom_Penh, and a
 * non-UTC comparison zone); every zone must yield identical results. The effective zone is logged
 * so the evidence report can attribute each run.
 */
@SpringBootTest
class ChannelScheduleTimeMappingIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProductChannelScheduleRepository schedules;

    private UUID store;
    private UUID product;
    private UUID channelRow;

    @BeforeEach
    void setUp() {
        store = insertStore();
        UUID cat = insertCategory();
        product = insertProduct(cat);
        insertStoreProduct();
        channelRow = insertChannelRow();
        schedules.deleteByProductChannelId(channelRow);
    }

    private String zoneLabel() {
        return TimeZone.getDefault().getID() + " / " + ZoneId.systemDefault();
    }

    // ----------------------------------------------------------------- A. raw SQL write -> JPA read
    @Test
    void rawSqlWriteReadsBackAsExactLocalTime() {
        jdbc.update("insert into catalog.product_channel_schedules (schedule_id, product_channel_id, day_of_week, "
                + "start_local_time, end_local_time, active, created_at, updated_at, version) "
                + "values (?,?::uuid,3,TIME '09:00:00',TIME '17:00:00',true,now(),now(),0)", UUID.randomUUID(),
                channelRow.toString());
        ProductChannelSchedule loaded = only();
        System.out.println("[3B2-A] zone=" + zoneLabel() + " raw->jpa start=" + loaded.startLocalTime() + " end="
                + loaded.endLocalTime());
        assertEquals(LocalTime.of(9, 0), loaded.startLocalTime(), "raw TIME '09:00' must map to LocalTime 09:00 ["
                + zoneLabel() + "]");
        assertEquals(LocalTime.of(17, 0), loaded.endLocalTime(), "raw TIME '17:00' must map to LocalTime 17:00 ["
                + zoneLabel() + "]");
    }

    // ----------------------------------------------------------------- B. JPA write -> raw SQL read
    @Test
    void jpaWriteStoresExactRawTime() {
        schedules.save(ProductChannelSchedule.create(channelRow, 3, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        String rawStart = jdbc.queryForObject("select start_local_time::text from catalog.product_channel_schedules "
                + "where product_channel_id = ?::uuid", String.class, channelRow.toString());
        String rawEnd = jdbc.queryForObject("select end_local_time::text from catalog.product_channel_schedules "
                + "where product_channel_id = ?::uuid", String.class, channelRow.toString());
        System.out.println("[3B2-B] zone=" + zoneLabel() + " jpa->raw start=" + rawStart + " end=" + rawEnd);
        assertEquals("09:00:00", rawStart, "JPA LocalTime.of(9,0) must persist literal 09:00:00 [" + zoneLabel()
                + "]");
        assertEquals("17:00:00", rawEnd, "JPA LocalTime.of(17,0) must persist literal 17:00:00 [" + zoneLabel() + "]");
    }

    // ----------------------------------------------------------------- C. JPA write -> JPA reload
    @Test
    void jpaRoundTripPreservesExactLocalTime() {
        schedules.save(ProductChannelSchedule.create(channelRow, 3, LocalTime.of(9, 0), LocalTime.of(17, 0), true));
        // Force a fresh read (new transaction -> new persistence context / connection).
        ProductChannelSchedule reloaded = only();
        System.out.println("[3B2-C] zone=" + zoneLabel() + " jpa->jpa start=" + reloaded.startLocalTime() + " end="
                + reloaded.endLocalTime());
        assertEquals(LocalTime.of(9, 0), reloaded.startLocalTime(), "JPA round-trip must preserve 09:00 ["
                + zoneLabel() + "]");
        assertEquals(LocalTime.of(17, 0), reloaded.endLocalTime(), "JPA round-trip must preserve 17:00 ["
                + zoneLabel() + "]");
    }

    // ----------------------------------------------------------------- D. canonical column contract (§4)
    @Test
    void scheduleBoundariesRemainTimeWithoutTimeZoneColumns() {
        List<String> types = jdbc.query(
                "select data_type from information_schema.columns where table_schema = 'catalog' "
                        + "and table_name = 'product_channel_schedules' and column_name in "
                        + "('start_local_time','end_local_time') order by column_name",
                (rs, i) -> rs.getString(1));
        System.out.println("[3B2-D] zone=" + zoneLabel() + " column types=" + types);
        assertEquals(List.of("time without time zone", "time without time zone"), types,
                "the fix is an ORM mapping correction only — the physical PostgreSQL type stays "
                        + "TIME WITHOUT TIME ZONE");
    }

    private ProductChannelSchedule only() {
        List<ProductChannelSchedule> all = schedules.findByProductChannelIdIn(List.of(channelRow));
        assertEquals(1, all.size(), "expected exactly one schedule row");
        return all.get(0);
    }

    // ----------------------------------------------------------------- fixtures
    private UUID insertStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'3B2 Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", id,
                "T2" + UUID.randomUUID().toString().substring(0, 8));
        return id;
    }

    private UUID insertCategory() {
        UUID id = UUID.randomUUID();
        String code = "T2C-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, display_order, "
                + "active, created_at, updated_at, version) values (?,?,?,10,true,now(),now(),0)", id, code, "C " + code);
        return id;
    }

    private UUID insertProduct(UUID categoryId) {
        UUID id = UUID.randomUUID();
        String code = "T2P-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD',5,0.10,false,true,now(),now(),0)", id, code, categoryId, "3B2 Product",
                new BigDecimal("3.00"));
        return id;
    }

    private void insertStoreProduct() {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, sold_out_until, updated_at, "
                + "version) values (?,?,true,null,now(),0)", store, product);
    }

    private UUID insertChannelRow() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, visible, "
                + "orderable, display_order, timezone, created_at, updated_at, version) "
                + "values (?,?,?,'CUSTOMER_WEB',true,true,0,'Asia/Phnom_Penh',now(),now(),0)", id, store, product);
        return id;
    }
}
