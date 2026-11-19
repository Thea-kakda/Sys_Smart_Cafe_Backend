package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.modules.catalog.domain.ProductChannelSchedule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChannelScheduleEvaluator} (Slice 3B, spec §7). These lock the
 * boundary semantics the whole catalog verdict depends on: empty == always open,
 * disabled rows ignored, start INCLUSIVE / end EXCLUSIVE, and correct overnight
 * handling across the ISO day boundary in an arbitrary store timezone. The instant is
 * always supplied explicitly (mirroring the injected {@link java.time.Clock}), never
 * read from the wall clock, so every assertion is deterministic.
 */
class ChannelScheduleEvaluatorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final UUID CHANNEL = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private static ProductChannelSchedule sched(int dow, String start, String end, boolean active) {
        return ProductChannelSchedule.create(CHANNEL, dow, LocalTime.parse(start), LocalTime.parse(end), active);
    }

    /** Instant at a given local date/time in the zone. Monday 2026-01-05 is ISO dow=1. */
    private static Instant at(String date, String time) {
        return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(UTC).toInstant();
    }

    @Test
    void emptyScheduleIsAlwaysOpen() {
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(), UTC, at("2026-01-05", "03:00")),
                "no enforced window means the channel is never schedule-blocked");
    }

    @Test
    void allDisabledScheduleIsAlwaysOpen() {
        var s = sched(1, "09:00", "17:00", false);
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-05", "12:00")),
                "disabled rows are ignored entirely");
    }

    @Test
    void normalWindowStartInclusiveEndExclusive() {
        var s = sched(1, "09:00", "17:00", true); // Monday
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-05", "09:00")),
                "start boundary is inclusive");
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-05", "16:59")),
                "just before end is open");
        assertFalse(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-05", "17:00")),
                "end boundary is exclusive");
        assertFalse(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-05", "08:59")),
                "before start is closed");
    }

    @Test
    void normalWindowDoesNotMatchOtherDay() {
        var s = sched(1, "09:00", "17:00", true); // Monday only
        assertFalse(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-06", "12:00")),
                "Tuesday is outside a Monday-only window");
    }

    @Test
    void overnightWindowCoversSameDayTail() {
        var s = sched(1, "22:00", "02:00", true); // Mon 22:00 -> Tue 02:00
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-05", "23:30")),
                "late Monday is within the tail");
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-05", "22:00")),
                "overnight start is inclusive");
    }

    @Test
    void overnightWindowCoversNextDayHead() {
        var s = sched(1, "22:00", "02:00", true); // Mon 22:00 -> Tue 02:00
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-06", "01:00")),
                "early Tuesday belongs to Monday's overnight window");
        assertFalse(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-06", "02:00")),
                "the head end is exclusive");
        assertFalse(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-06", "12:00")),
                "midday Tuesday is not covered by any window");
    }

    @Test
    void overnightWindowWrapsSundayToMonday() {
        var s = sched(7, "23:00", "01:00", true); // Sun 23:00 -> Mon 01:00
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-05", "00:30")),
                "Monday 00:30 falls inside Sunday's overnight head (yesterdayDow wraps 1->7)");
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(s), UTC, at("2026-01-04", "23:30")),
                "Sunday 23:30 is inside the tail");
    }

    @Test
    void multipleWindowsAnyMatchOpens() {
        var morning = sched(1, "07:00", "09:00", true);
        var evening = sched(1, "18:00", "20:00", true);
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(morning, evening), UTC, at("2026-01-05", "18:30")));
        assertFalse(ChannelScheduleEvaluator.isOpenNow(List.of(morning, evening), UTC, at("2026-01-05", "12:00")),
                "the gap between two same-day windows stays closed");
    }

    @Test
    void evaluatesInChannelZoneNotServerLocal() {
        // Window is defined in Asia/Bangkok (UTC+7); 09:00 local == 02:00 UTC.
        var bangkok = ZoneId.of("Asia/Bangkok");
        var s = sched(1, "09:00", "17:00", true); // Monday Bangkok local
        // Monday 2026-01-05 in Bangkok local: build the instant from that zone directly.
        Instant openLocal = LocalDate.of(2026, 1, 5).atTime(9, 0).atZone(bangkok).toInstant();
        assertTrue(ChannelScheduleEvaluator.isOpenNow(List.of(s), bangkok, openLocal),
                "zone-aware evaluation opens exactly at 09:00 local");
        Instant beforeLocal = LocalDate.of(2026, 1, 5).atTime(8, 59).atZone(bangkok).toInstant();
        assertFalse(ChannelScheduleEvaluator.isOpenNow(List.of(s), bangkok, beforeLocal),
                "one local minute before open stays closed even though UTC differs");
    }
}
