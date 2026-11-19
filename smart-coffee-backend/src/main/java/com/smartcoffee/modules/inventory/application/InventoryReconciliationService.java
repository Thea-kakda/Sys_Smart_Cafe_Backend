package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.infrastructure.retry.TransientRetryExecutor;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.InventoryReservationLine;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFinding;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationRun;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReservationAnomalyType;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.ReconciliationFindingRepository;
import com.smartcoffee.modules.inventory.persistence.ReconciliationRow;
import com.smartcoffee.modules.inventory.persistence.ReconciliationRunRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservation reconciliation (Slice 2E, spec §7/§13/§20). The read-only report is
 * the primary product: every detector is a bounded set-based query, so a store
 * scan costs a fixed number of queries regardless of row count. Findings are
 * written as <b>report records only</b> — nothing here mutates stock on the scan
 * path.
 *
 * <p>When {@code dryRun=false} the caller opts in to a repair pass. Repairs are
 * NOT part of the scan transaction: each eligible finding is repaired through
 * {@link ReservationRepairService} inside its OWN transaction (via
 * {@link TransientRetryExecutor}), and the repair re-validates the live state
 * under a lock. A stale finding is superseded rather than applied; an ambiguous
 * one degrades to manual review. This is why a dry run provably writes no stock.
 *
 * <p>Authorization is enforced here, not only in the controller (spec §14): the
 * run needs {@code reconciliation:run}, the repair path additionally needs
 * {@code reconciliation:repair}, a store-scoped run must be inside the caller's
 * store scope, and a cross-store (global) run is restricted to global
 * administrators.
 */
@Service
public class InventoryReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciliationService.class);

    private final InventoryReconciliationQueries queries;
    private final ReconciliationRunRepository runs;
    private final ReconciliationFindingRepository findings;
    private final InventoryReservationRepository reservations;
    private final RepairPolicy policy;
    private final ReservationRepairService repair;
    private final TransientRetryExecutor retry;
    private final ReservationMetrics metrics;
    private final Clock clock;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;
    private final int globalStoreCap;

    public InventoryReconciliationService(InventoryReconciliationQueries queries, ReconciliationRunRepository runs,
            ReconciliationFindingRepository findings, InventoryReservationRepository reservations, RepairPolicy policy,
            ReservationRepairService repair, TransientRetryExecutor retry, ReservationMetrics metrics, Clock clock,
            PermissionEvaluator permissions, StoreAuthorizationService stores,
            @Value("${app.inventory.reconciliation-global-store-cap:200}") int globalStoreCap) {
        this.queries = queries;
        this.runs = runs;
        this.findings = findings;
        this.reservations = reservations;
        this.policy = policy;
        this.repair = repair;
        this.retry = retry;
        this.metrics = metrics;
        this.clock = clock;
        this.permissions = permissions;
        this.stores = stores;
        this.globalStoreCap = globalStoreCap;
    }

    /** Bounded per-scan item cap so no single query can pull an unbounded result set. */
    public static int clampMaxItems(Integer requested) {
        int max = requested == null || requested <= 0 ? 500 : requested;
        return Math.min(max, 5000);
    }

    /** Summary returned by a run (maps directly to the §13 response contract). */
    public record RunResult(UUID runId, Instant startedAt, Instant completedAt, int scannedCount, int anomalyCount,
            int automaticallyRepairableCount, int manuallyReviewableCount, int repairedCount, int failedRepairCount,
            boolean dryRun) {
    }

    /**
     * Runs a reconciliation scan (and, when {@code dryRun=false}, an automatic
     * repair pass). Not itself transactional: the scan commits in
     * {@link #scanAndRecord}, then repairs run one-by-one in their own transactions.
     */
    public RunResult run(CurrentActor actor, UUID storeId, boolean dryRun, Integer maxItems) {
        permissions.require(actor, PermissionCodes.RECONCILIATION_RUN);
        int cap = clampMaxItems(maxItems);
        boolean global = storeId == null;
        if (global && !actor.globalAccess()) {
            throw new ValidationException("GLOBAL_SCAN_REQUIRES_ADMIN", "Cross-store reconciliation is admin-only");
        }
        if (!global) {
            stores.requireStore(actor, storeId);
        }
        List<UUID> targetStores = global ? boundedStoreIds() : List.of(storeId);

        metrics.increment(ReservationMetrics.RECON_RUNS, "dryRun", String.valueOf(dryRun),
                "scope", global ? "global" : "store");
        ScanResult scan = metrics.time(ReservationMetrics.RECON_DURATION, () -> scanAndRecord(targetStores, dryRun, cap,
                actor.userId()));

        int repaired = 0;
        int failed = 0;
        if (!dryRun && !scan.repairableFindingIds().isEmpty()) {
            permissions.require(actor, PermissionCodes.RECONCILIATION_REPAIR);
            for (UUID findingId : scan.repairableFindingIds()) {
                ReservationRepairService.Outcome outcome;
                try {
                    outcome = retry.execute("reconciliation_repair",
                            () -> repair.repair(findingId, null, "automatic reconciliation repair",
                                    "AUTO-RECON-" + findingId));
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn("reconciliation repair failed findingId={} error={}", findingId, ex.toString());
                    continue;
                }
                if (outcome == ReservationRepairService.Outcome.REPAIRED) {
                    repaired++;
                }
            }
            recordRepairOutcome(scan.runId(), repaired, failed);
        }
        return new RunResult(scan.runId(), scan.startedAt(), clock.instant(), scan.scannedCount(),
                scan.anomalyCount(), scan.autoRepairableCount(), scan.manualReviewCount(), repaired, failed, dryRun);
    }

    /**
     * Creates the run row, executes every bounded detector for the target stores,
     * persists the findings and the run tallies. A dry run performs EXACTLY this
     * and nothing more — it writes only report rows, never stock.
     */
    @Transactional
    ScanResult scanAndRecord(List<UUID> storeIds, boolean dryRun, int maxItems, UUID initiatedBy) {
        Instant now = clock.instant();
        boolean global = storeIds.size() > 1;
        ReconciliationRun run = ReconciliationRun.start(global ? null : storeIds.get(0), dryRun, now, initiatedBy);
        runs.saveAndFlush(run);

        long scanned = 0;
        List<ReconciliationFinding> found = new ArrayList<>();
        for (UUID storeId : storeIds) {
            scanned += queries.countReservations(storeId);
            scanStore(storeId, maxItems, now, run.id(), found);
        }
        // Global-only detectors (a reservation line whose parent is gone is not attributable to a store).
        if (global) {
            for (ReconciliationRow row : queries.orphanReservationLine(maxItems)) {
                found.add(toFinding(run.id(), ReservationAnomalyType.ORPHAN_RESERVATION_LINE, row, now));
            }
        }

        int auto = 0;
        List<UUID> repairableIds = new ArrayList<>();
        for (ReconciliationFinding f : found) {
            if (policy.potentiallyRepairable(f.anomalyType())) {
                f.markRepairable();
                auto++;
                repairableIds.add(f.id());
            } else {
                f.markManualReview();
            }
        }
        findings.saveAll(found);
        run.complete((int) Math.min(scanned, Integer.MAX_VALUE), found.size(), auto, found.size() - auto,
                clock.instant());
        runs.save(run);

        metrics.increment(ReservationMetrics.RECON_ANOMALIES, "severity", "any");
        return new ScanResult(run.id(), now, (int) Math.min(scanned, Integer.MAX_VALUE), found.size(), auto,
                found.size() - auto, repairableIds);
    }

    /** Persists a repair pass's tallies onto the (already-completed) run header. */
    @Transactional
    void recordRepairOutcome(UUID runId, int repaired, int failed) {
        runs.findById(runId).ifPresent(run -> {
            run.recordRepairOutcome(repaired, failed);
            runs.save(run);
        });
    }

    /**
     * Operator-driven repair of a previous run's still-REPAIRABLE findings (§13).
     * Distinct from the automatic pass in {@link #run}: here the actor is a real
     * user and a non-empty reason is mandatory (spec §11). Each finding is repaired
     * in its own transaction and re-validated live, so a stale report row is
     * superseded, never applied. Already-repaired findings drop out of the queue,
     * making a repeated call idempotent.
     */
    public RunResult repairRun(CurrentActor actor, UUID runId, String reason, UUID idempotencyKey) {
        permissions.require(actor, PermissionCodes.RECONCILIATION_REPAIR);
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("REPAIR_REASON_REQUIRED", "A manual repair requires a non-empty reason");
        }
        ReconciliationRun run = runs.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("RECON_RUN_NOT_FOUND", "Reconciliation run not found"));
        if (run.storeId() != null) {
            stores.requireStore(actor, run.storeId());
        } else if (!actor.globalAccess()) {
            throw new ValidationException("CROSS_STORE_REPAIR_REQUIRES_ADMIN", "Cross-store repair is admin-only");
        }
        List<ReconciliationFinding> queue = findings.repairableForRun(runId);
        int repaired = 0;
        int failed = 0;
        for (ReconciliationFinding f : queue) {
            String key = (idempotencyKey == null ? "OP" : idempotencyKey.toString()) + "-" + f.id();
            try {
                ReservationRepairService.Outcome outcome = retry.execute("reconciliation_repair",
                        () -> repair.repair(f.id(), actor.userId(), reason, key));
                if (outcome == ReservationRepairService.Outcome.REPAIRED) {
                    repaired++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("operator reconciliation repair failed findingId={} error={}", f.id(), ex.toString());
            }
        }
        recordRepairOutcome(runId, repaired, failed);
        return new RunResult(runId, run.startedAt(), clock.instant(), run.scannedCount(), run.anomalyCount(),
                run.autoRepairableCount(), run.manualReviewCount(), repaired, failed, false);
    }

    /** Store-scoped (or admin-global) open-findings page for {@code GET .../reconciliation}. */
    @Transactional(readOnly = true)
    public Page<ReconciliationFinding> pageFindings(CurrentActor actor, UUID storeId, int page, int size) {
        permissions.require(actor, PermissionCodes.RECONCILIATION_VIEW);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        if (storeId != null) {
            stores.requireStore(actor, storeId);
            return findings.openForStore(storeId, pageable);
        }
        if (!actor.globalAccess()) {
            throw new ValidationException("CROSS_STORE_VIEW_REQUIRES_ADMIN",
                    "Cross-store reconciliation view is admin-only");
        }
        return findings.openGlobal(pageable);
    }

    /** Operational detail for one reservation (§13). No customer personal data. */
    @Transactional(readOnly = true)
    public ReservationDetailView reservationDetail(CurrentActor actor, UUID reservationId) {
        permissions.require(actor, PermissionCodes.RECONCILIATION_VIEW);
        InventoryReservation r = reservations.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("RESERVATION_NOT_FOUND", "Reservation not found"));
        stores.requireStore(actor, r.storeId());
        List<ReservationLineView> lines = new ArrayList<>();
        for (InventoryReservationLine line : r.lines()) {
            lines.add(new ReservationLineView(line.ingredientId(), line.quantityReserved(), line.baseUnitCode()));
        }
        List<UUID> ledgerIds = queries.ledgerTransactionIds(reservationId);
        List<String> anomalies = findings.openForReservation(reservationId).stream()
                .map(f -> f.anomalyType().name()).toList();
        return new ReservationDetailView(r.id(), r.storeId(), r.sourceType(), r.status().name(), r.orderId(),
                r.cartId(), r.demandRevision(), r.expiresAt(), r.convertedAt(), r.consumedAt(), r.releasedAt(),
                r.releaseReason(), lines, ledgerIds, anomalies);
    }

    /** Reservation-detail projection (safe operational fields only). */
    public record ReservationDetailView(UUID reservationId, UUID storeId, String sourceType, String status,
            UUID orderId, UUID cartId, long demandRevision, Instant expiresAt, Instant convertedAt,
            Instant consumedAt, Instant releasedAt, String releaseReason, List<ReservationLineView> lines,
            List<UUID> relatedTransactionIds, List<String> detectedAnomalies) {
    }

    /** One persisted reservation snapshot line. */
    public record ReservationLineView(UUID inventoryItemId, BigDecimal quantityReserved, String baseUnitCode) {
    }

    // ------------------------------------------------------------- detectors

    private void scanStore(UUID storeId, int maxItems, Instant now, UUID runId, List<ReconciliationFinding> out) {
        // balance-vs-derived: one query, three outcome shapes decided per row.
        for (ReconciliationRow row : queries.balanceVsDerived(storeId, maxItems)) {
            out.add(toFinding(runId, balanceAnomaly(row), row, now));
        }
        addAll(out, runId, ReservationAnomalyType.NEGATIVE_BALANCE_STATE, queries.negativeBalance(storeId, maxItems),
                now);
        addAll(out, runId, ReservationAnomalyType.EXPIRED_ACTIVE_RESERVATION,
                queries.expiredActive(storeId, now, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.ACTIVE_CART_RESERVATION_ON_CHECKED_OUT_CART,
                queries.activeCartOnCheckedOutCart(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER,
                queries.activeOrderOnCancelledOrder(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER,
                queries.activeOrderOnPaidOrder(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.CONVERTED_RESERVATION_WITHOUT_TARGET,
                queries.convertedWithoutTarget(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.CONVERTED_QUANTITY_MISMATCH,
                queries.convertedQuantityMismatch(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.CONSUMED_RESERVATION_WITHOUT_CONSUMPTION_LEDGER,
                queries.consumedWithoutLedger(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.DUPLICATE_CONSUMPTION_LEDGER,
                queries.duplicateConsumption(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.RELEASED_RESERVATION_WITHOUT_RELEASE_LEDGER,
                queries.releasedWithoutLedger(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.RELEASE_AND_CONSUMPTION_CONFLICT,
                queries.releaseAndConsumptionConflict(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.ORPHAN_RESERVATION, queries.orphanReservation(storeId, maxItems),
                now);
        addAll(out, runId, ReservationAnomalyType.ORPHAN_LEDGER_REFERENCE,
                queries.orphanLedgerReference(storeId, maxItems), now);
        addAll(out, runId, ReservationAnomalyType.RESERVATION_STORE_MISMATCH,
                queries.storeMismatch(storeId, maxItems), now);
    }

    private void addAll(List<ReconciliationFinding> out, UUID runId, ReservationAnomalyType type,
            List<ReconciliationRow> rows, Instant now) {
        for (ReconciliationRow row : rows) {
            out.add(toFinding(runId, type, row, now));
        }
    }

    /** Decides which of the three balance-derived anomalies a FULL OUTER JOIN row is. */
    private ReservationAnomalyType balanceAnomaly(ReconciliationRow row) {
        BigDecimal derived = row.expected();
        BigDecimal recorded = row.actual();
        if (recorded == null) {
            return ReservationAnomalyType.ACTIVE_RESERVATION_WITHOUT_BALANCE;
        }
        if (derived == null || derived.signum() == 0) {
            return ReservationAnomalyType.BALANCE_WITHOUT_ACTIVE_RESERVATION;
        }
        return ReservationAnomalyType.RESERVED_BALANCE_MISMATCH;
    }

    private ReconciliationFinding toFinding(UUID runId, ReservationAnomalyType type, ReconciliationRow row,
            Instant now) {
        if (type == ReservationAnomalyType.ORPHAN_RESERVATION || type == ReservationAnomalyType.ORPHAN_RESERVATION_LINE
                || type == ReservationAnomalyType.ORPHAN_LEDGER_REFERENCE) {
            metrics.increment(ReservationMetrics.ORPHAN_DETECTED, "class", type.name());
        }
        if (type == ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER) {
            metrics.increment(ReservationMetrics.PAID_UNCONSUMED_DETECTED, "outcome", "detected");
        }
        return ReconciliationFinding.open(runId, row.storeId(), row.itemId(), row.reservationId(), type,
                row.expected(), row.actual(), row.orderId(), row.cartId(), null, now);
    }

    private List<UUID> boundedStoreIds() {
        List<UUID> all = queries.listActiveStoreIds();
        return all.size() <= globalStoreCap ? all : all.subList(0, globalStoreCap);
    }

    /** Internal scan tallies + the repair-queue ids produced by a scan. */
    record ScanResult(UUID runId, Instant startedAt, int scannedCount, int anomalyCount, int autoRepairableCount,
            int manualReviewCount, List<UUID> repairableFindingIds) {
    }
}
