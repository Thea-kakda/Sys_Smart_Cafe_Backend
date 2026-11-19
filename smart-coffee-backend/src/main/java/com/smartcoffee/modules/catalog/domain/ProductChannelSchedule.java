package com.smartcoffee.modules.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One weekly availability window for a {@link ProductChannel} (Slice 3B, spec §7).
 * Windows are interpreted in the channel/store local timezone; {@code dayOfWeek}
 * uses ISO numbering (Monday = 1 .. Sunday = 7) to match {@link java.time.DayOfWeek}.
 *
 * <p>An overnight window is expressed as {@code start > end} (e.g. 22:00 &rarr;
 * 02:00). The start boundary is inclusive and the end boundary exclusive; that
 * evaluation lives in {@code ChannelScheduleEvaluator}, not here. Disabled
 * ({@code active == false}) rows are ignored by the evaluator; a channel with no
 * schedule rows is treated as always available.
 */
@Entity
@Table(schema = "catalog", name = "product_channel_schedules")
public class ProductChannelSchedule {

    @Id
    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "product_channel_id", nullable = false, updatable = false)
    private UUID productChannelId;
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;
    // Slice 3B.2 canonical contract: catalog.product_channel_schedules.{start,end}_local_time are
    // PostgreSQL TIME WITHOUT TIME ZONE wall-clock values (interpreted in the Store/ProductChannel
    // ZoneId) that MUST map to java.time.LocalTime with NO timezone conversion whatsoever, so a raw
    // SQL INSERT, a Flyway seed, a CSV import and an ETL job are all read exactly as written.
    // Hibernate's default resolution for a LocalTime attribute is the SqlTypes.TIME (92) jdbc type,
    // i.e. TimeJdbcType, which round-trips the value through an absolute java.sql.Time and applies
    // the global hibernate.jdbc.time_zone calendar on top of the JVM default zone — shifting the
    // stored wall clock by the JVM offset (+07 here) on every raw write and inverting it on write.
    // Binding the fields to SqlTypes.LOCAL_TIME (3011, LocalTimeJdbcType) keeps the wall-clock
    // semantics: the value goes to PostgreSQL as a literal time through setObject(LocalTime) and is
    // never touched by a Calendar, so the same row reads identically under every JVM timezone while
    // the physical column stays TIME WITHOUT TIME ZONE and hibernate.jdbc.time_zone stays UTC for
    // the Instant/OffsetDateTime columns that genuinely need UTC normalisation.
    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "start_local_time", nullable = false)
    private LocalTime startLocalTime;
    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "end_local_time", nullable = false)
    private LocalTime endLocalTime;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(nullable = false)
    private long version;

    protected ProductChannelSchedule() {
    }

    public static ProductChannelSchedule create(UUID productChannelId, int dayOfWeek, LocalTime start, LocalTime end,
            boolean active) {
        if (productChannelId == null) {
            throw new IllegalArgumentException("Product channel is required");
        }
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new IllegalArgumentException("Day of week must be ISO 1..7");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("Schedule window start and end are required");
        }
        if (start.equals(end)) {
            throw new IllegalArgumentException("Schedule window start must differ from end");
        }
        var s = new ProductChannelSchedule();
        s.id = UUID.randomUUID();
        s.productChannelId = productChannelId;
        s.dayOfWeek = (short) dayOfWeek;
        s.startLocalTime = start;
        s.endLocalTime = end;
        s.active = active;
        var now = Instant.now();
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    public void changeWindow(int dayOfWeek, LocalTime start, LocalTime end, boolean active) {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new IllegalArgumentException("Day of week must be ISO 1..7");
        }
        if (start == null || end == null || start.equals(end)) {
            throw new IllegalArgumentException("Schedule window start and end are required and must differ");
        }
        this.dayOfWeek = (short) dayOfWeek;
        this.startLocalTime = start;
        this.endLocalTime = end;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID productChannelId() {
        return productChannelId;
    }

    public int dayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime startLocalTime() {
        return startLocalTime;
    }

    public LocalTime endLocalTime() {
        return endLocalTime;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
