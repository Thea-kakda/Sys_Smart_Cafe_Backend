package com.smartcoffee.modules.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smartcoffee.modules.organization.domain.Store;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Slice 3B.2 (§11) timestamp non-regression guard. The schedule correction must be a
 * <b>field-level</b> change only: {@code hibernate.jdbc.time_zone=UTC} stays in force because
 * every application timestamp column in this schema is {@code TIMESTAMP WITH TIME ZONE}
 * (141 of them) and Payment / Cart / Order / reservation / audit / outbox correctness depends
 * on that UTC normalisation. This class proves the absolute-instant path is untouched:
 *
 * <ul>
 *   <li>the global setting is still {@code UTC},</li>
 *   <li>a raw SQL {@code TIMESTAMPTZ} literal loads through JPA as exactly that instant,</li>
 *   <li>a JPA {@code Instant} write stores exactly that instant (verified as UTC-normalised
 *       text, not through the JVM default zone),</li>
 *   <li>the driver-level {@code getObject(.., OffsetDateTime.class)} read path used by the
 *       outbox and reservation-expiry repositories still yields the identical instant.</li>
 * </ul>
 *
 * <p>Runs under every {@code -Duser.timezone} the gate uses; the assertions are JVM-zone
 * invariant by construction, so a shift in the global timezone handling would fail here.
 */
@SpringBootTest
class TimestampTimezoneNonRegressionIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StoreRepository stores;
    @Autowired
    Environment env;

    private String zoneLabel() {
        return TimeZone.getDefault().getID() + " / " + ZoneId.systemDefault();
    }

    @Test
    void globalHibernateTimeZoneRemainsUtc() {
        String zone = env.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone");
        System.out.println("[3B2-TS] hibernate.jdbc.time_zone=" + zone + " jvm=" + zoneLabel());
        assertEquals("UTC", zone,
                "Slice 3B.2 must fix the wall-clock LocalTime mapping at the field level and must NOT "
                        + "remove the global UTC timezone that protects every timestamptz column");
    }

    @Test
    void rawSqlTimestampLiteralReadsBackAsTheSameInstant() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) values "
                + "(?,?,?,'Addr','Asia/Phnom_Penh','USD','en-US',true,"
                + "TIMESTAMPTZ '2026-01-07 02:00:00+00',TIMESTAMPTZ '2026-01-07 03:30:00+00',0)", id,
                "TS" + id.toString().substring(0, 8), "TS Raw Store");

        Store loaded = stores.findById(id).orElseThrow();
        System.out.println("[3B2-TS] raw->jpa created=" + loaded.createdAt() + " updated=" + loaded.updatedAt()
                + " jvm=" + zoneLabel());
        assertEquals(Instant.parse("2026-01-07T02:00:00Z"), loaded.createdAt(),
                "a raw TIMESTAMPTZ literal must survive the JPA read unchanged [" + zoneLabel() + "]");
        assertEquals(Instant.parse("2026-01-07T03:30:00Z"), loaded.updatedAt(),
                "a raw TIMESTAMPTZ literal must survive the JPA read unchanged [" + zoneLabel() + "]");
    }

    @Test
    void jpaInstantWriteStoresTheExactAbsoluteMoment() {
        // A microsecond-exact instant: PostgreSQL TIMESTAMPTZ stores microseconds, so a value taken
        // from Instant.now() (100ns granularity on this JVM) would legitimately lose sub-microsecond
        // digits. The fixed instant makes the comparison exact and therefore meaningful.
        Instant fixed = Instant.parse("2026-01-07T02:00:00.123456Z");
        Store saved = persistWithFixedTimestamps("TSJ", fixed, fixed.plusSeconds(5400));

        // Read the stored value as UTC-normalised text: independent of the session and JVM zones.
        String dbText = jdbc.queryForObject("select to_char(created_at at time zone 'UTC', "
                + "'YYYY-MM-DD HH24:MI:SS.US') from organization.stores where store_id = ?", String.class, saved.id());
        System.out.println("[3B2-TS] jpa->raw created=" + dbText + " instant=" + fixed + " jvm=" + zoneLabel());
        assertEquals("2026-01-07 02:00:00.123456", dbText,
                "the JPA Instant write must land on exactly the same absolute UTC moment [" + zoneLabel() + "]");

        // The offset-date read path used by JdbcOutboxClaimRepository / ReservationExpiryClaimRepository.
        Instant offsetRead = jdbc.queryForObject("select created_at from organization.stores where store_id = ?",
                (rs, i) -> rs.getObject("created_at", OffsetDateTime.class).toInstant(), saved.id());
        assertEquals(fixed, offsetRead, "OffsetDateTime -> Instant conversion must stay exact");
    }

    @Test
    void driverTimestampReadMatchesTheJpaEntityInstant() {
        Instant fixed = Instant.parse("2026-08-15T23:59:59.999999Z");
        Store saved = persistWithFixedTimestamps("TSD", fixed, fixed);
        Instant jdbcInstant = jdbc.queryForObject("select created_at from organization.stores where store_id = ?",
                (rs, i) -> rs.getTimestamp("created_at").toInstant(), saved.id());
        Store reloaded = stores.findById(saved.id()).orElseThrow();
        System.out.println("[3B2-TS] jdbc=" + jdbcInstant + " jpa=" + reloaded.createdAt() + " jvm=" + zoneLabel());
        assertEquals(fixed, jdbcInstant, "raw JDBC Timestamp read must equal the JPA write [" + zoneLabel() + "]");
        assertEquals(fixed, reloaded.createdAt(), "JPA reload must equal the raw JDBC read [" + zoneLabel() + "]");
    }

    /** Saves a store whose audit timestamps are fixed, not {@code Instant.now()} (see above). */
    private Store persistWithFixedTimestamps(String prefix, Instant created, Instant updated) {
        Store s = Store.create(prefix + UUID.randomUUID().toString().substring(0, 8), "TS Store", "Addr", null,
                "Asia/Phnom_Penh", "USD", "en-US", true);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "createdAt", created);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "updatedAt", updated);
        return stores.save(s);
    }
}
