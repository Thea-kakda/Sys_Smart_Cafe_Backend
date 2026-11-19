package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.modules.catalog.domain.ProductChannelSchedule;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Evaluates whether a channel is currently within its weekly schedule (Slice 3B,
 * spec §7). Pure over the supplied active schedules, zone and instant — the caller
 * supplies the instant from the injected {@link java.time.Clock}, never
 * {@code LocalDateTime.now()}, so behavior is deterministic and testable.
 *
 * <p>Semantics:
 * <ul>
 *   <li>An <b>empty</b> (or all-disabled) schedule means <b>always available</b>.</li>
 *   <li>Disabled rows are ignored.</li>
 *   <li>Windows are interpreted in the channel/store zone (no server-local
 *       assumptions); {@code dayOfWeek} is ISO 1..7.</li>
 *   <li>Start boundary is <b>inclusive</b>, end boundary <b>exclusive</b>.</li>
 *   <li>An overnight window ({@code start > end}, e.g. 22:00 &rarr; 02:00) covers
 *       the same-day tail from {@code start} to midnight AND the following-day head
 *       from midnight to {@code end}. Multiple windows per day are supported.</li>
 * </ul>
 */
public final class ChannelScheduleEvaluator {

    private ChannelScheduleEvaluator() {
    }

    public static boolean isOpenNow(List<ProductChannelSchedule> schedules, ZoneId zone, Instant now) {
        List<ProductChannelSchedule> active = schedules.stream().filter(schedule -> schedule.active()).toList();
        if (active.isEmpty()) {
            return true; // no enforced window -> always available
        }
        ZonedDateTime local = now.atZone(zone);
        LocalTime t = local.toLocalTime();
        int todayDow = local.getDayOfWeek().getValue();
        int yesterdayDow = todayDow == 1 ? 7 : todayDow - 1;

        for (ProductChannelSchedule s : active) {
            LocalTime start = s.startLocalTime();
            LocalTime end = s.endLocalTime();
            boolean open;
            if (start.isBefore(end)) { // normal same-day window [start, end)
                open = s.dayOfWeek() == todayDow && !t.isBefore(start) && t.isBefore(end);
            } else { // overnight window: same-day [start, 24:00) + next-day [00:00, end)
                boolean sameDayTail = s.dayOfWeek() == todayDow && !t.isBefore(start);
                boolean previousDayHead = s.dayOfWeek() == yesterdayDow && t.isBefore(end);
                open = sameDayTail || previousDayHead;
            }
            if (open) {
                return true;
            }
        }
        return false;
    }
}
