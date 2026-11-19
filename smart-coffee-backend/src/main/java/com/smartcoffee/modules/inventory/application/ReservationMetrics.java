package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.infrastructure.observability.MetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Reservation operational metrics (Slice 2E, spec §17). One façade so every
 * counter/gauge/timer name is declared once and none of them ever carries a
 * Store id or a reservation id as a tag — identifiers belong in structured logs,
 * not metric labels, to keep cardinality bounded.
 *
 * <p>Low-cardinality tags that ARE allowed ({@code outcome}, {@code sourceType},
 * {@code anomalyClass}, {@code dryRun}) each have a small fixed domain.
 */
@Component
public class ReservationMetrics {

    // ---- counters
    public static final String EXPIRED_PROCESSED = "reservation.expired.processed";
    public static final String EXPIRED_RELEASED = "reservation.expired.released";
    public static final String EXPIRED_FAILED = "reservation.expired.failed";
    public static final String RECON_RUNS = "reservation.reconciliation.runs";
    public static final String RECON_ANOMALIES = "reservation.reconciliation.anomalies";
    public static final String RECON_REPAIRED = "reservation.reconciliation.repaired";
    public static final String RECON_FAILED = "reservation.reconciliation.failed";
    public static final String ADMIN_RELEASE = "reservation.admin_release";
    public static final String RETRY = "reservation.retry";
    public static final String RETRY_EXHAUSTED = "reservation.retry_exhausted";
    public static final String PAID_UNCONSUMED_DETECTED = "reservation.paid_unconsumed.detected";
    public static final String ORPHAN_DETECTED = "reservation.orphan.detected";

    // ---- gauges (settable snapshots; see the pending/active collectors below)
    public static final String ACTIVE_CART = "reservation.active.cart";
    public static final String ACTIVE_ORDER = "reservation.active.order";
    public static final String EXPIRED_PENDING = "reservation.expired.pending";
    public static final String ANOMALY_COUNT = "reservation.anomaly.count";
    public static final String BALANCE_MISMATCH_TOTAL = "reservation.balance_mismatch.total";

    // ---- timers
    public static final String EXPIRY_DURATION = "reservation.expiry.duration";
    public static final String RECON_DURATION = "reservation.reconciliation.duration";
    public static final String REPAIR_DURATION = "reservation.repair.duration";

    private final MetricsService metrics;
    private final MeterRegistry registry;

    // Cached gauge holders so scheduled collectors update instead of re-binding.
    private final AtomicLong activeCart = new AtomicLong();
    private final AtomicLong activeOrder = new AtomicLong();
    private final AtomicLong expiredPending = new AtomicLong();
    private final AtomicLong anomalyCount = new AtomicLong();
    private final AtomicLong balanceMismatchTotal = new AtomicLong();
    private volatile boolean gaugesBound;

    public ReservationMetrics(MetricsService metrics, MeterRegistry registry) {
        this.metrics = metrics;
        this.registry = registry;
    }

    public void increment(String name, String... tags) {
        metrics.increment(name, tags);
    }

    /** Times a worker batch and records the duration under {@code timerName}. */
    public <T> T time(String timerName, Supplier<T> body) {
        Timer.Sample sample = Timer.start(registry);
        try {
            return body.get();
        } finally {
            sample.stop(registry.timer(timerName));
        }
    }

    public void recordDuration(String timerName, Duration duration) {
        registry.timer(timerName).record(duration);
    }

    /**
     * Publishes the latest bounded gauge snapshot. Called by the scheduled
     * collectors; registers the gauges lazily on the first call so nothing reads
     * an AtomicLong before it has a value.
     */
    public void publishGauges(long activeCarts, long activeOrders, long pendingExpired, long openAnomalies,
            long balanceMismatches) {
        activeCart.set(activeCarts);
        activeOrder.set(activeOrders);
        expiredPending.set(pendingExpired);
        anomalyCount.set(openAnomalies);
        balanceMismatchTotal.set(balanceMismatches);
        bindGauges();
    }

    private synchronized void bindGauges() {
        if (gaugesBound) {
            return;
        }
        registry.gauge(ACTIVE_CART, activeCart, counter -> counter.doubleValue());
        registry.gauge(ACTIVE_ORDER, activeOrder, counter -> counter.doubleValue());
        registry.gauge(EXPIRED_PENDING, expiredPending, counter -> counter.doubleValue());
        registry.gauge(ANOMALY_COUNT, anomalyCount, counter -> counter.doubleValue());
        registry.gauge(BALANCE_MISMATCH_TOTAL, balanceMismatchTotal, counter -> counter.doubleValue());
        gaugesBound = true;
    }
}
