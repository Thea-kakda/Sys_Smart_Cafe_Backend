package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.catalog.api.ProductSummaryResponse;
import com.smartcoffee.modules.catalog.application.ChannelCatalogService;
import com.smartcoffee.modules.catalog.application.ChannelManagementService;
import com.smartcoffee.modules.catalog.api.ChannelConfigurationRequest;
import com.smartcoffee.modules.catalog.api.ReplaceSchedulesRequest;
import com.smartcoffee.modules.catalog.domain.Channel;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Schedule integration tests (Slice 3B.1, spec §4) across the PERSISTENCE and SERVICE
 * layers with an injected, controllable {@link Clock} (never {@code Thread.sleep}).
 * Weekly windows are stored as local times and evaluated in the STORE timezone against
 * the absolute instant the clock supplies, so the tests pin exact boundary semantics:
 * inclusive start, exclusive end, overnight wrap, inactive rows ignored, empty schedule
 * == always open, and the {@code ChannelManagementService} validation rules (zero-length
 * window, invalid day, overlap, stale version, atomic replace-all).
 *
 * <p>Membership/visibility is unaffected by schedules (they only gate {@code orderable}),
 * so each scenario asserts the {@code CHANNEL_OUTSIDE_SCHEDULE} reason code rather than
 * presence, keeping the schedule engine isolated from recipe/inventory setup.
 */
@SpringBootTest
class ChannelScheduleIT extends IntegrationTestBase {

    /** A clock whose instant the test drives directly; deterministic, sleep-free. */
    static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-01-07T00:00:00Z");

        void set(Instant i) {
            instant = i;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Phnom_Penh"); // UTC+7, no DST

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ChannelCatalogService catalog;
    @Autowired
    ChannelManagementService management;
    @Autowired
    com.smartcoffee.common.security.CurrentActorProvider actors;
    @Autowired
    MutableClock clock;
    @Autowired
    com.smartcoffee.modules.catalog.persistence.ProductChannelScheduleRepository schedRepo;

    private UUID store;
    private UUID product;

    @BeforeEach
    void setUp() {
        store = newStore();
        UUID cat = newCategory();
        product = newProduct(cat);
        offer(store, product);
        // Drive the REAL ChannelCatalogService with the controllable clock by injecting it
        // straight into the transactional proxy's target, so window boundaries are proven
        // through the actual persistence/service stack (no bean-override ambiguity, no sleep).
        Object target = AopTestUtils.getUltimateTargetObject(catalog);
        ReflectionTestUtils.setField(target, "clock", clock);
    }

    /** Seeds an explicit visible+orderable channel row; returns its product_channel_id. */
    private UUID publishChannel() {
        UUID channelId = UUID.randomUUID();
        jdbc.update("insert into catalog.product_channels (product_channel_id, store_id, product_id, channel, visible, "
                + "orderable, display_order, timezone, created_at, updated_at, version) "
                + "values (?,?,?,?,true,true,0,?,now(),now(),0)", channelId, store, product,
                Channel.CUSTOMER_WEB.name(), STORE_ZONE.getId());
        return channelId;
    }

    /**
     * Seeds a schedule window through RAW SQL (TIME literals), i.e. exactly what a Flyway seed,
     * a CSV import or an ETL job would write and what the catalog service must then read back
     * unshifted (Slice 3B.2, spec §8 "Persistence source: Raw SQL-seeded schedule"). This is the
     * interoperability path that Slice 3B.1 could not prove; the {@code SqlTypes.LOCAL_TIME}
     * binding on {@code ProductChannelSchedule} is what makes it work.
     */
    private void addWindow(UUID channelId, int dow, String start, String end, boolean active) {
        jdbc.update("insert into catalog.product_channel_schedules (schedule_id, product_channel_id, day_of_week, "
                + "start_local_time, end_local_time, active, created_at, updated_at, version) "
                + "values (?,?::uuid,?,?::time,?::time,?,now(),now(),0)", UUID.randomUUID(),
                channelId.toString(), dow, start, end, active);
    }

    /** Same window, written through JPA ({@code ChannelManagementService.replaceSchedules} path). */
    private void addWindowViaJpa(UUID channelId, int dow, String start, String end, boolean active) {
        schedRepo.save(com.smartcoffee.modules.catalog.domain.ProductChannelSchedule.create(
                channelId, dow, LocalTime.parse(start), LocalTime.parse(end), active));
    }

    /** Absolute instant for a given local date/time in the STORE zone. */
    private static Instant at(int year, int month, int day, int hour, int minute, int second) {
        return ZonedDateTime.of(year, month, day, hour, minute, second, 0, STORE_ZONE).toInstant();
    }

    private boolean scheduleClosedAt(Instant instant) {
        clock.set(instant);
        ProductSummaryResponse row = catalog.list(store, Channel.CUSTOMER_WEB, "", null, false, 0, 50).getContent()
                .stream().filter(r -> r.productId().equals(product)).findFirst().orElseThrow();
        return row.reasonCodes().contains("CHANNEL_OUTSIDE_SCHEDULE");
    }

    // ------------------------------------------------------- same-day inclusive/exclusive boundaries

    @Test
    void sameDayWindowStartIsInclusiveEndIsExclusive() {
        // 2026-01-07 is a Wednesday (dow=3). Window 09:00 -> 17:00.
        UUID channelId = publishChannel();
        addWindow(channelId, 3, "09:00", "17:00", true);

        assertTrue(!scheduleClosedAt(at(2026, 1, 7, 9, 0, 0)), "09:00 start is inclusive -> open");
        assertTrue(!scheduleClosedAt(at(2026, 1, 7, 16, 59, 59)), "16:59:59 is within window -> open");
        assertTrue(scheduleClosedAt(at(2026, 1, 7, 17, 0, 0)), "17:00 end is exclusive -> closed");
        assertTrue(scheduleClosedAt(at(2026, 1, 7, 8, 59, 59)), "before 09:00 -> closed");
    }

    // --------------------------------------------------------------------- overnight wrap

    @Test
    void overnightWindowWrapsIntoNextDay() {
        // 2026-01-05 is a Monday (dow=1). Window Mon 22:00 -> Tue 02:00 (start > end).
        UUID channelId = publishChannel();
        addWindow(channelId, 1, "22:00", "02:00", true);

        assertTrue(!scheduleClosedAt(at(2026, 1, 5, 22, 0, 0)), "Mon 22:00 open");
        assertTrue(!scheduleClosedAt(at(2026, 1, 5, 23, 59, 0)), "Mon 23:59 open");
        assertTrue(!scheduleClosedAt(at(2026, 1, 6, 1, 0, 0)), "Tue 01:00 open through Monday's overnight tail");
        assertTrue(scheduleClosedAt(at(2026, 1, 6, 2, 0, 0)), "Tue 02:00 end exclusive -> closed");
        assertTrue(scheduleClosedAt(at(2026, 1, 6, 12, 0, 0)), "Tue midday closed");
    }

    // ------------------------------------------------------------- Slice 3B.2 §8: day wrapping (Sun -> Mon)

    @Test
    void overnightWindowWrapsFromSundayIntoMonday() {
        // 2026-01-04 is a Sunday (dow=7), 2026-01-05 the following Monday (dow=1).
        UUID channelId = publishChannel();
        addWindow(channelId, 7, "22:00", "02:00", true);

        assertTrue(!scheduleClosedAt(at(2026, 1, 4, 22, 0, 0)), "Sun 22:00 open");
        assertTrue(!scheduleClosedAt(at(2026, 1, 4, 23, 59, 59)), "Sun 23:59:59 open");
        assertTrue(!scheduleClosedAt(at(2026, 1, 5, 1, 0, 0)), "Mon 01:00 open through Sunday's overnight tail");
        assertTrue(scheduleClosedAt(at(2026, 1, 5, 2, 0, 0)), "Mon 02:00 end exclusive -> closed");
    }

    // ------------------------------- Slice 3B.2 §8: raw-SQL and JPA seeds must behave IDENTICALLY

    /**
     * The acceptance rule "all persistence sources must return identical business results for
     * identical stored local times". The same Wed 09:00&rarr;17:00 and Mon 22:00&rarr;02:00 windows are
     * seeded once through RAW SQL and once through JPA, and the boundary verdict is probed at the
     * same eight instants in both runs; the two result vectors must match exactly. Before the
     * {@code SqlTypes.LOCAL_TIME} fix the raw-seeded vector was shifted by +07 (open 16:00&ndash;00:00
     * instead of 09:00&ndash;17:00), so the vectors differed and this test failed.
     */
    @Test
    void rawSeededAndJpaSeededSchedulesProduceIdenticalBoundaries() {
        List<Instant> probes = List.of(
                at(2026, 1, 7, 8, 59, 59), at(2026, 1, 7, 9, 0, 0), at(2026, 1, 7, 16, 59, 59),
                at(2026, 1, 7, 17, 0, 0), at(2026, 1, 5, 21, 59, 59), at(2026, 1, 5, 22, 0, 0),
                at(2026, 1, 6, 1, 59, 59), at(2026, 1, 6, 2, 0, 0));

        UUID channelId = publishChannel();
        addWindow(channelId, 3, "09:00", "17:00", true);
        addWindow(channelId, 1, "22:00", "02:00", true);
        List<Boolean> rawSeeded = probes.stream().map(this::scheduleClosedAt).toList();

        // Same channel, same two windows, this time written through JPA.
        jdbc.update("delete from catalog.product_channel_schedules where product_channel_id = ?", channelId);
        addWindowViaJpa(channelId, 3, "09:00", "17:00", true);
        addWindowViaJpa(channelId, 1, "22:00", "02:00", true);
        List<Boolean> jpaSeeded = probes.stream().map(this::scheduleClosedAt).toList();

        // And the stored literals must be identical too, not merely behave identically.
        List<String> rawLiterals = jdbc.query("select start_local_time::text || '-' || end_local_time::text "
                + "from catalog.product_channel_schedules where product_channel_id = ?::uuid order by day_of_week",
                (rs, i) -> rs.getString(1), channelId.toString());
        assertEquals(List.of("22:00:00-02:00:00", "09:00:00-17:00:00"), rawLiterals,
                "JPA must store the same wall-clock literals a raw import would");
        assertEquals(rawSeeded, jpaSeeded, "raw SQL seed and JPA seed must yield identical availability");
        assertEquals(List.of(true, false, false, true, true, false, false, true), rawSeeded,
                "closed at 08:59:59 / open 09:00 / open 16:59:59 / closed 17:00 / closed Mon 02:00");
    }

    // --------------------------------------------------------------------- multiple windows

    @Test
    void multipleWindowsSameDayBothApply() {
        UUID channelId = publishChannel();
        addWindow(channelId, 3, "07:00", "09:00", true); // morning
        addWindow(channelId, 3, "17:00", "19:00", true); // evening

        assertTrue(!scheduleClosedAt(at(2026, 1, 7, 8, 0, 0)), "morning window open");
        assertTrue(scheduleClosedAt(at(2026, 1, 7, 12, 0, 0)), "midday closed (gap between windows)");
        assertTrue(!scheduleClosedAt(at(2026, 1, 7, 18, 0, 0)), "evening window open");
    }

    // ------------------------------------------------------------- inactive ignored / empty == open

    @Test
    void inactiveWindowsAreIgnoredAndEmptyScheduleMeansAlwaysOpen() {
        UUID channelId = publishChannel();
        addWindow(channelId, 3, "09:00", "10:00", false); // inactive -> ignored

        // With only an inactive window the schedule is empty -> always open, even far from it.
        assertTrue(!scheduleClosedAt(at(2026, 1, 7, 23, 0, 0)), "inactive-only schedule => always open");

        // A channel with no windows at all is likewise always open.
        jdbc.update("delete from catalog.product_channel_schedules where product_channel_id = ?", channelId);
        assertTrue(!scheduleClosedAt(at(2026, 1, 7, 3, 0, 0)), "no windows => always open");
    }

    // --------------------------------------------------------- store timezone drives the result

    @Test
    void evaluationUsesStoreTimezoneNotServerLocal() {
        // Window Wed 09:00 -> 17:00 in the STORE zone (Asia/Phnom_Penh, UTC+7).
        UUID channelId = publishChannel();
        addWindow(channelId, 3, "09:00", "17:00", true);

        // 02:00Z == 09:00 store-local (open). 10:00Z == 17:00 store-local (closed).
        // The clock instant is absolute; the JVM default zone is irrelevant because the
        // evaluator converts the instant into the store zone, so this passes identically
        // no matter what ZoneId the host runs under.
        assertTrue(!scheduleClosedAt(Instant.parse("2026-01-07T02:00:00Z")), "09:00 store-local is open");
        assertTrue(scheduleClosedAt(Instant.parse("2026-01-07T10:00:00Z")), "17:00 store-local is closed");
    }

    // ------------------------------------------------------------- service validation rules

    @Test
    void replaceSchedulesValidatesShapeOverlapAndVersionAndIsAtomic() {
        UUID channelId = publishChannel();
        CurrentActor admin = seedAdminActor();

        // zero-length window rejected.
        assertThrows(ValidationException.class, () -> management.replaceSchedules(admin, store, product,
                Channel.CUSTOMER_WEB,
                new ReplaceSchedulesRequest(List.of(new ReplaceSchedulesRequest.Window(1, LocalTime.of(9, 0),
                        LocalTime.of(9, 0), true)), null)));

        // invalid day rejected.
        assertThrows(ValidationException.class, () -> management.replaceSchedules(admin, store, product,
                Channel.CUSTOMER_WEB,
                new ReplaceSchedulesRequest(List.of(new ReplaceSchedulesRequest.Window(9, LocalTime.of(9, 0),
                        LocalTime.of(17, 0), true)), null)));

        // overlapping windows rejected.
        assertThrows(ConflictException.class, () -> management.replaceSchedules(admin, store, product,
                Channel.CUSTOMER_WEB,
                new ReplaceSchedulesRequest(List.of(new ReplaceSchedulesRequest.Window(1, LocalTime.of(9, 0),
                        LocalTime.of(17, 0), true), new ReplaceSchedulesRequest.Window(1, LocalTime.of(12, 0),
                        LocalTime.of(20, 0), true)), null)));

        // valid replace-all persists atomically (two windows, previous rows gone).
        addWindow(channelId, 2, "08:00", "12:00", true); // a pre-existing row to be replaced
        var saved = management.replaceSchedules(admin, store, product, Channel.CUSTOMER_WEB,
                new ReplaceSchedulesRequest(List.of(new ReplaceSchedulesRequest.Window(1, LocalTime.of(9, 0),
                        LocalTime.of(12, 0), true), new ReplaceSchedulesRequest.Window(1, LocalTime.of(14, 0),
                        LocalTime.of(18, 0), true)), null));
        assertEquals(2, saved.size());
        List<Integer> days = jdbc.query("select day_of_week from catalog.product_channel_schedules where "
                + "product_channel_id = ?", (rs, i) -> (int) rs.getShort(1), channelId);
        assertEquals(2, days.size(), "replace-all is atomic: exactly the new windows remain");
        assertEquals(0, jdbc.queryForObject("select count(*) from catalog.product_channel_schedules where "
                + "product_channel_id = ? and day_of_week = 2", Integer.class, channelId),
                "the old Tuesday row was removed by the replace");

        // stale version rejected (channel now at version 0; a correct call bumps to 1).
        long currentVersion = jdbc.queryForObject("select version from catalog.product_channels where "
                + "product_channel_id = ?", Long.class, channelId);
        management.upsertChannel(admin, store, product, Channel.CUSTOMER_WEB,
                new ChannelConfigurationRequest(true, true, null, null, 0, null, null, null, currentVersion));
        long fresh = jdbc.queryForObject("select version from catalog.product_channels where product_channel_id = ?",
                Long.class, channelId);
        assertThrows(ConflictException.class, () -> management.replaceSchedules(admin, store, product,
                Channel.CUSTOMER_WEB, new ReplaceSchedulesRequest(List.of(), fresh + 5)));
    }

    // --------------------------- Slice 3B.2 §9: an overnight window must only occupy the next calendar day

    /**
     * Guards the {@code detectOverlap} correction. An overnight window used to be extended by a
     * full week, so {@code Mon 22:00 -> 02:00} collided with EVERY other window in the week and the
     * management API rejected the ordinary "9to5 plus late night" configuration. The week-wrap copy,
     * not the extension, is what must make a Sunday tail collide with an early-Monday window.
     */
    @Test
    void overnightWindowNeverConflictsWithWindowsOnOtherDays() {
        publishChannel();
        CurrentActor admin = seedAdminActor();

        // Accepted: Mon 22:00->02:00 and Wed 09:00->17:00 are disjoint on the weekly timeline.
        var saved = management.replaceSchedules(admin, store, product, Channel.CUSTOMER_WEB,
                new ReplaceSchedulesRequest(List.of(new ReplaceSchedulesRequest.Window(1, LocalTime.of(22, 0),
                        LocalTime.of(2, 0), true), new ReplaceSchedulesRequest.Window(3, LocalTime.of(9, 0),
                        LocalTime.of(17, 0), true)), 0L));
        assertEquals(2, saved.size(), "overnight + a mid-week window is a valid configuration");

        // Still rejected: Sunday 22:00 -> 02:00 really does run into Monday 02:00, so an explicit
        // Monday 01:00->03:00 window overlaps it across the day boundary. expectedVersion is null so
        // the version guard is skipped and only the overlap rule can produce the conflict.
        assertThrows(ConflictException.class, () -> management.replaceSchedules(admin, store, product,
                Channel.CUSTOMER_WEB, new ReplaceSchedulesRequest(List.of(
                        new ReplaceSchedulesRequest.Window(7, LocalTime.of(22, 0), LocalTime.of(2, 0), true),
                        new ReplaceSchedulesRequest.Window(1, LocalTime.of(1, 0), LocalTime.of(3, 0), true)), null)));

        // Still rejected: plain same-day overlap.
        assertThrows(ConflictException.class, () -> management.replaceSchedules(admin, store, product,
                Channel.CUSTOMER_WEB, new ReplaceSchedulesRequest(List.of(
                        new ReplaceSchedulesRequest.Window(5, LocalTime.of(9, 0), LocalTime.of(17, 0), true),
                        new ReplaceSchedulesRequest.Window(5, LocalTime.of(16, 0), LocalTime.of(20, 0), true)), null)));
    }

    // ------------------------------------------------------------------ fixtures

    private UUID newStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'SCHED Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)", id,
                "SC" + UUID.randomUUID().toString().substring(0, 8));
        return id;
    }

    private UUID newCategory() {
        UUID id = UUID.randomUUID();
        String code = "SCE-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, display_order, "
                + "active, created_at, updated_at, version) values (?,?,?,10,true,now(),now(),0)", id, code, "C " + code);
        return id;
    }

    private UUID newProduct(UUID categoryId) {
        UUID id = UUID.randomUUID();
        String code = "SPR-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'USD',5,0.10,false,true,now(),now(),0)", id, code, categoryId, "Sched Product",
                new BigDecimal("3.00"));
        return id;
    }

    private void offer(UUID store, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, sold_out_until, updated_at, "
                + "version) values (?,?,true,null,now(),0)", store, productId);
    }

    /** Seeds a global SUPER_ADMIN user, authenticates the thread, and resolves its actor. */
    private CurrentActor seedAdminActor() {
        UUID userId = UUID.randomUUID();
        String email = "sched-admin-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Sched Admin", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,null,now())", UUID.randomUUID(), userId,
                UUID.fromString("00000000-0000-0000-0000-000000000002"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        userId.toString(), null, List.of()));
        return actors.require();
    }
}
