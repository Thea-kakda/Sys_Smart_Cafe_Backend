package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.modules.inventory.application.ReservationOperationalMonitor.Snapshot;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Inventory reservation health component (Slice 2E, spec §18). Reads ONLY the
 * {@link ReservationOperationalMonitor}'s cached snapshot — it never scans the
 * database, so a health poll costs no queries and cannot be used to hammer the
 * reservation tables. No quantities or identifiers are exposed publicly; the
 * details are plain counts, and the underlying numbers come from bounded indexed
 * COUNTs refreshed on a schedule.
 *
 * <p>States:
 * <ul>
 *   <li><b>DOWN</b> — an open CRITICAL finding (duplicate consumption, a
 *       release/consumption conflict, a critical balance mismatch) exists, or the
 *       collector itself has gone stale (its worker is failing);</li>
 *   <li><b>DEGRADED</b> — an open ERROR finding exists or expired holds are pending
 *       past their TTL (the cleanup worker is lagging), within tolerance;</li>
 *   <li><b>UP</b> — the latest bounded check found nothing critical.</li>
 * </ul>
 */
@Component("reservationHealthIndicator")
public class ReservationHealthIndicator implements HealthIndicator {

    private final ReservationOperationalMonitor monitor;
    private final Clock clock;

    public ReservationHealthIndicator(ReservationOperationalMonitor monitor, Clock clock) {
        this.monitor = monitor;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Snapshot s = monitor.snapshot();
        if (s == null) {
            return Health.up().withDetail("state", "awaiting-first-collection").build();
        }
        Duration age = Duration.between(s.asOf(), clock.instant());
        if (age.compareTo(monitor.staleAfter()) > 0) {
            return Health.down().withDetail("reason", "reservation-collector-stale")
                    .withDetail("snapshotAgeSeconds", age.toSeconds()).build();
        }
        if (s.openCritical() > 0) {
            return Health.down().withDetail("reason", "critical-reservation-anomaly")
                    .withDetail("openCritical", s.openCritical()).build();
        }
        if (s.openError() > 0 || s.expiredPending() > 0) {
            return Health.status("DEGRADED").withDetail("openError", s.openError())
                    .withDetail("expiredPending", s.expiredPending()).build();
        }
        return Health.up().build();
    }
}
