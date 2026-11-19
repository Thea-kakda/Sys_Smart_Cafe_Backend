package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartcoffee.modules.inventory.application.ReservationOperationalMonitor.Snapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Slice 2E reservation health tests (spec §18). The indicator must read ONLY the
 * cached snapshot (never hit the DB) and map it to UP / DEGRADED / DOWN:
 * an open CRITICAL or a stale collector is DOWN, an open ERROR or an expiry backlog
 * is DEGRADED, otherwise UP; and a not-yet-collected state is UP (awaiting-first).
 */
class ReservationHealthIndicatorTest {

    private ReservationOperationalMonitor monitor;
    private Clock clock;
    private ReservationHealthIndicator indicator;
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");

    @BeforeEach
    void setup() {
        monitor = mock(ReservationOperationalMonitor.class);
        clock = Clock.fixed(now, ZoneOffset.UTC);
        indicator = new ReservationHealthIndicator(monitor, clock);
        when(monitor.staleAfter()).thenReturn(Duration.ofMinutes(15));
    }

    @Test
    void readsOnlyTheCachedSnapshot() {
        when(monitor.snapshot()).thenReturn(null);
        indicator.health();
        verify(monitor).snapshot();
        verifyNoMoreInteractions(monitor); // no DB-touching collaborator invoked
    }

    @Test
    void awaitingFirstCollectionIsUp() {
        when(monitor.snapshot()).thenReturn(null);
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void staleCollectorIsDownEvenWhenCountsAreClean() {
        when(monitor.snapshot()).thenReturn(snapshot(0, 0, 0, 0, 0, 0, 0, now.minus(Duration.ofMinutes(20))));
        Health health = indicator.health();
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("reservation-collector-stale", health.getDetails().get("reason"));
    }

    @Test
    void openCriticalIsDown() {
        when(monitor.snapshot()).thenReturn(snapshot(1, 1, 0, 1, 0, 1, 0, now));
        Health health = indicator.health();
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("critical-reservation-anomaly", health.getDetails().get("reason"));
    }

    @Test
    void openErrorIsDegraded() {
        when(monitor.snapshot()).thenReturn(snapshot(1, 1, 0, 1, 0, 0, 2, now));
        assertEquals("DEGRADED", indicator.health().getStatus().getCode());
    }

    @Test
    void expiredBacklogIsDegraded() {
        when(monitor.snapshot()).thenReturn(snapshot(1, 1, 4, 0, 0, 0, 0, now));
        assertEquals("DEGRADED", indicator.health().getStatus().getCode());
    }

    @Test
    void everythingHealthyIsUp() {
        when(monitor.snapshot()).thenReturn(snapshot(10, 5, 0, 0, 0, 0, 0, now));
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    private static Snapshot snapshot(long activeCart, long activeOrder, long expiredPending, long openAnomalies,
            long balanceMismatch, long openCritical, long openError, Instant asOf) {
        return new Snapshot(activeCart, activeOrder, expiredPending, openAnomalies, balanceMismatch, openCritical,
                openError, asOf);
    }
}
