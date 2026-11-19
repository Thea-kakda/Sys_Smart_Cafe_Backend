package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFindingStatus;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationSeverity;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReservationAnomalyType;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.ReconciliationFindingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded reservation operational collector (Slice 2E, spec §17/§18). On a fixed
 * schedule it reads a handful of indexed COUNT queries — never a per-row scan —
 * publishes them as low-cardinality gauges, and caches the latest snapshot so the
 * Actuator health component can answer without touching the database on every
 * request (spec §18: "Do not perform a full database scan on every health request").
 *
 * <p>All labels/counts here are aggregate numbers; no store id or reservation id is
 * ever a metric label (identifiers belong in structured logs, not gauges).
 */
@Component
public class ReservationOperationalMonitor {

    private static final Logger log = LoggerFactory.getLogger(ReservationOperationalMonitor.class);
    private static final List<ReconciliationFindingStatus> LIVE = List.of(ReconciliationFindingStatus.OPEN,
            ReconciliationFindingStatus.REPAIRABLE, ReconciliationFindingStatus.MANUAL_REVIEW);

    private final InventoryReconciliationQueries queries;
    private final ReconciliationFindingRepository findings;
    private final ReservationMetrics metrics;
    private final Clock clock;
    private final Duration staleAfter;

    /** Latest snapshot; null until the first sweep. The health gate degrades gracefully when absent. */
    private final AtomicReference<Snapshot> latest = new AtomicReference<>();

    public ReservationOperationalMonitor(InventoryReconciliationQueries queries, ReconciliationFindingRepository findings,
            ReservationMetrics metrics, Clock clock,
            @Value("${app.inventory.health.stale-after:PT15M}") Duration staleAfter) {
        this.queries = queries;
        this.findings = findings;
        this.metrics = metrics;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    /** Cached operational verdict, read by {@link ReservationHealthIndicator}. */
    public record Snapshot(long activeCart, long activeOrder, long expiredPending, long openAnomalies,
            long balanceMismatch, long openCritical, long openError, Instant asOf) {
    }

    public Snapshot snapshot() {
        return latest.get();
    }

    Duration staleAfter() {
        return staleAfter;
    }

    @Scheduled(fixedDelayString = "${app.inventory.gauge-poll-ms:120000}")
    public void collect() {
        try {
            Instant now = clock.instant();
            long activeCart = queries.countActiveBySource("CART_ITEM");
            long activeOrder = queries.countActiveBySource("ORDER");
            long expiredPending = queries.countExpiredPending(now);
            long openAnomalies = findings.countByStatusIn(LIVE);
            long balanceMismatch = findings.countByAnomalyTypeAndStatusIn(
                    ReservationAnomalyType.RESERVED_BALANCE_MISMATCH, LIVE);
            long openCritical = findings.countByStatusAndSeverityIn(ReconciliationFindingStatus.OPEN,
                    List.of(ReconciliationSeverity.CRITICAL));
            long openError = findings.countByStatusAndSeverityIn(ReconciliationFindingStatus.OPEN,
                    List.of(ReconciliationSeverity.ERROR));
            metrics.publishGauges(activeCart, activeOrder, expiredPending, openAnomalies, balanceMismatch);
            latest.set(new Snapshot(activeCart, activeOrder, expiredPending, openAnomalies, balanceMismatch,
                    openCritical, openError, now));
        } catch (RuntimeException e) {
            // A failed collection must not crash the scheduler thread; the previous
            // snapshot is kept and, once older than the stale window, health reports DOWN.
            log.warn("reservation operational gauge collection failed", e);
        }
    }
}
