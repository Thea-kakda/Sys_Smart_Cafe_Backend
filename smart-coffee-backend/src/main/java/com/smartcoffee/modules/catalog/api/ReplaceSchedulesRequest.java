package com.smartcoffee.modules.catalog.api;

import java.time.LocalTime;
import java.util.List;

/**
 * Replace-all payload for a channel's weekly schedule (Slice 3B, spec §7/§18). The
 * list is authoritative: absent windows are removed. {@code dayOfWeek} is ISO 1..7;
 * an overnight window is {@code start > end}; start is inclusive and end exclusive.
 * {@code expectedVersion} is the channel row's optimistic-lock version (schedules
 * are owned by the channel aggregate). Overlapping same-day windows are rejected
 * with 409 SCHEDULE_OVERLAP.
 */
public record ReplaceSchedulesRequest(
        List<Window> schedules,
        Long expectedVersion) {

    public record Window(int dayOfWeek, LocalTime startTime, LocalTime endTime, boolean active) {
    }
}
