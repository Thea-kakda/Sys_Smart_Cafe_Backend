package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.infrastructure.observability.MetricsService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2E metrics façade tests (spec §17/§27 item 9). Uses a real
 * {@link SimpleMeterRegistry} so the assertions are about the meters actually
 * published — counters increment, gauges hold the latest bounded snapshot, timers
 * record — and, critically, that no gauge/counter ever carries a high-cardinality
 * identifier tag.
 */
class ReservationMetricsTest {

    private SimpleMeterRegistry registry;
    private ReservationMetrics metrics;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        metrics = new ReservationMetrics(new MetricsService(registry), registry);
    }

    @Test
    void countersIncrementWithTheirTags() {
        metrics.increment(ReservationMetrics.EXPIRED_PROCESSED, "sourceType", "CART");
        metrics.increment(ReservationMetrics.EXPIRED_PROCESSED, "sourceType", "CART");
        metrics.increment(ReservationMetrics.EXPIRED_PROCESSED, "sourceType", "ORDER");

        assertEquals(2.0, registry.get(ReservationMetrics.EXPIRED_PROCESSED).tag("sourceType", "CART").counter()
                .count());
        assertEquals(1.0, registry.get(ReservationMetrics.EXPIRED_PROCESSED).tag("sourceType", "ORDER").counter()
                .count());
    }

    @Test
    void dryRunAndRepairAreDistinctMetricSeries() {
        metrics.increment(ReservationMetrics.RECON_RUNS, "dryRun", "true", "scope", "store");
        metrics.increment(ReservationMetrics.RECON_RUNS, "dryRun", "false", "scope", "store");

        double dry = registry.get(ReservationMetrics.RECON_RUNS).tag("dryRun", "true").counter().count();
        double wet = registry.get(ReservationMetrics.RECON_RUNS).tag("dryRun", "false").counter().count();
        assertEquals(1.0, dry);
        assertEquals(1.0, wet);
    }

    @Test
    void publishGaugesReflectsTheLatestBoundedSnapshot() {
        metrics.publishGauges(12, 7, 3, 5, 2);
        assertEquals(12.0, gauge(ReservationMetrics.ACTIVE_CART).value());
        assertEquals(7.0, gauge(ReservationMetrics.ACTIVE_ORDER).value());
        assertEquals(3.0, gauge(ReservationMetrics.EXPIRED_PENDING).value());
        assertEquals(5.0, gauge(ReservationMetrics.ANOMALY_COUNT).value());
        assertEquals(2.0, gauge(ReservationMetrics.BALANCE_MISMATCH_TOTAL).value());

        // A second publish updates the same gauge (no re-binding, no duplicate meters).
        metrics.publishGauges(20, 0, 0, 1, 0);
        assertEquals(20.0, gauge(ReservationMetrics.ACTIVE_CART).value());
        assertEquals(1, registry.find(ReservationMetrics.ACTIVE_CART).gauges().size(),
                "publishing twice must not register a second gauge");
    }

    @Test
    void gaugesCarryNoTagsSoCardinalityStaysBounded() {
        metrics.publishGauges(1, 1, 1, 1, 1);
        registry.forEachMeter(meter -> {
            if (meter instanceof Gauge) {
                assertTrue(meter.getId().getTags().isEmpty(),
                        "reservation gauge must never carry a tag (identifier leak): " + meter.getId());
            }
        });
    }

    @Test
    void noRegisteredMeterUsesAUuidLikeTagValue() {
        metrics.increment(ReservationMetrics.ADMIN_RELEASE, "released", "true");
        metrics.publishGauges(1, 1, 1, 1, 1);
        registry.forEachMeter(meter -> meter.getId().getTags().forEach(tag -> assertFalse(
                tag.getValue().matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
                "metric tag value looks like an identifier: " + tag)));
    }

    @Test
    void timeRecordsTheSuppliedBodyAndReturnsItsValue() {
        String out = metrics.time(ReservationMetrics.EXPIRY_DURATION, () -> "ok");
        assertEquals("ok", out);
        assertEquals(1, registry.get(ReservationMetrics.EXPIRY_DURATION).timer().count());
    }

    @Test
    void recordDurationAddsToTheTimer() {
        metrics.recordDuration(ReservationMetrics.RECON_DURATION, Duration.ofMillis(5));
        assertEquals(1, registry.get(ReservationMetrics.RECON_DURATION).timer().count());
    }

    private Gauge gauge(String name) {
        return registry.get(name).gauge();
    }
}
