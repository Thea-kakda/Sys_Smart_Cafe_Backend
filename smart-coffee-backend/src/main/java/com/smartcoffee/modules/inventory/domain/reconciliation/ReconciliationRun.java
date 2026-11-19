package com.smartcoffee.modules.inventory.domain.reconciliation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One reconciliation execution (Slice 2E). A run is a report header: it records
 * what was scanned and how many findings of each class were seen. It never holds
 * authoritative stock; the counters describe the scan, not the balances.
 *
 * <p>Mapped to {@code inventory.inventory_reconciliation_runs} (V047). The
 * {@code parameters} snapshot column is intentionally not mapped as an entity
 * field — it is written only where a caller needs it and read back purely for
 * diagnostics, so it stays out of the hot object graph.
 */
@Entity
@Table(schema = "inventory", name = "inventory_reconciliation_runs")
public class ReconciliationRun {
    @Id
    @Column(name = "run_id")
    private UUID id;
    /** Null for an authorized global (cross-store) run. */
    @Column(name = "store_id")
    private UUID storeId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconciliationRunStatus status;
    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "scanned_count", nullable = false)
    private int scannedCount;
    @Column(name = "anomaly_count", nullable = false)
    private int anomalyCount;
    @Column(name = "auto_repairable_count", nullable = false)
    private int autoRepairableCount;
    @Column(name = "manual_review_count", nullable = false)
    private int manualReviewCount;
    @Column(name = "repaired_count", nullable = false)
    private int repairedCount;
    @Column(name = "failed_repair_count", nullable = false)
    private int failedRepairCount;
    @Column(name = "initiated_by")
    private UUID initiatedBy;
    @Version
    private long version;

    protected ReconciliationRun() {
    }

    public static ReconciliationRun start(UUID storeId, boolean dryRun, Instant now, UUID initiatedBy) {
        var r = new ReconciliationRun();
        r.id = UUID.randomUUID();
        r.storeId = storeId;
        r.dryRun = dryRun;
        r.status = ReconciliationRunStatus.RUNNING;
        r.startedAt = now;
        r.initiatedBy = initiatedBy;
        return r;
    }

    /** Records the scan tallies and moves the run to a terminal status. */
    public void complete(int scanned, int anomalies, int autoRepairable, int manualReview, Instant now) {
        this.scannedCount = scanned;
        this.anomalyCount = anomalies;
        this.autoRepairableCount = autoRepairable;
        this.manualReviewCount = manualReview;
        this.completedAt = now;
        this.status = anomalies > 0 ? ReconciliationRunStatus.COMPLETED_WITH_FINDINGS : ReconciliationRunStatus.COMPLETED;
    }

    public void fail(Instant now) {
        this.completedAt = now;
        this.status = ReconciliationRunStatus.FAILED;
    }

    /** Accumulates the outcome of a repair pass (a non-dry run). */
    public void recordRepairOutcome(int repaired, int failed) {
        this.repairedCount += repaired;
        this.failedRepairCount += failed;
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public ReconciliationRunStatus status() {
        return status;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public int scannedCount() {
        return scannedCount;
    }

    public int anomalyCount() {
        return anomalyCount;
    }

    public int autoRepairableCount() {
        return autoRepairableCount;
    }

    public int manualReviewCount() {
        return manualReviewCount;
    }

    public int repairedCount() {
        return repairedCount;
    }

    public int failedRepairCount() {
        return failedRepairCount;
    }

    public UUID initiatedBy() {
        return initiatedBy;
    }
}
