package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.ReservationStatus;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFinding;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFindingStatus;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.OrderSnapshot;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.PaymentSettlement;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.ReconciliationFindingRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a single reconciliation repair (Slice 2E, spec §10/§11/§13). The golden
 * rules enforced here:
 *
 * <ul>
 *   <li><b>never repair stale report data</b> — the finding's live subject is
 *       re-validated under a row lock before anything moves, and if the anomaly
 *       is gone the finding is superseded, not applied;</li>
 *   <li><b>no second algorithm</b> — a release goes through
 *       {@link InventoryReservationFinalizationService#releaseOrder} and a
 *       consumption through {@link PaidOrderRecoveryService}, which is itself the
 *       settlement path; the ledger rows and their idempotency are owned there;</li>
 *   <li><b>never guess</b> — only the two policy-approved actions are attempted,
 *       and the paid-but-unconsumed action still requires all nine §9 conditions;</li>
 *   <li><b>auditable</b> — a repair writes an audit entry and stamps the finding
 *       with actor, reason, idempotency key and timestamp.</li>
 * </ul>
 *
 * <p>The reconciliation batch (dryRun=false) calls this through
 * {@link com.smartcoffee.infrastructure.retry.TransientRetryExecutor} so each
 * repair is its own transaction and a transient DB failure retries safely.
 */
@Service
public class ReservationRepairService {

    private static final Logger log = LoggerFactory.getLogger(ReservationRepairService.class);

    private final ReconciliationFindingRepository findings;
    private final InventoryReservationRepository reservations;
    private final InventoryReconciliationQueries queries;
    private final InventoryReservationFinalizationService finalization;
    private final PaidOrderRecoveryService recovery;
    private final RepairPolicy policy;
    private final AuditWriter audit;
    private final ReservationMetrics metrics;

    public ReservationRepairService(ReconciliationFindingRepository findings, InventoryReservationRepository reservations,
            InventoryReconciliationQueries queries, InventoryReservationFinalizationService finalization,
            PaidOrderRecoveryService recovery, RepairPolicy policy, AuditWriter audit, ReservationMetrics metrics) {
        this.findings = findings;
        this.reservations = reservations;
        this.queries = queries;
        this.finalization = finalization;
        this.recovery = recovery;
        this.policy = policy;
        this.audit = audit;
        this.metrics = metrics;
    }

    public enum Outcome {
        /** the guarded action was applied and stock changed */
        REPAIRED,
        /** already in the desired state; nothing moved (idempotent success) */
        NO_CHANGE,
        /** the anomaly is no longer present on the live row; finding superseded */
        SUPERSEDED,
        /** ambiguous / condition unmet; finding demoted to manual review */
        MANUAL_REVIEW
    }

    /**
     * Revalidate + apply the repair for one finding. {@code actor} may be null for
     * a system-initiated (batch) repair; a manual (operator) repair requires a
     * non-empty reason.
     */
    @Transactional
    public Outcome repair(UUID findingId, UUID actor, String reason, String idempotencyKey) {
        ReconciliationFinding finding = findings.findById(findingId)
                .orElseThrow(() -> new ConflictException("FINDING_NOT_FOUND", "Reconciliation finding does not exist"));
        if (finding.status() == ReconciliationFindingStatus.REPAIRED) {
            // Idempotent: a repeated repair of the same finding is a no-op.
            return Outcome.NO_CHANGE;
        }
        if (finding.status() != ReconciliationFindingStatus.OPEN && finding.status() != ReconciliationFindingStatus.REPAIRABLE) {
            throw new ConflictException("FINDING_NOT_REPAIRABLE",
                    "Finding is " + finding.status() + " and cannot be repaired");
        }
        // Manual (operator) repair must justify itself (spec §11/§13).
        if (actor != null && (reason == null || reason.isBlank())) {
            throw new ValidationException("REPAIR_REASON_REQUIRED", "A manual repair requires a non-empty reason");
        }
        RepairPolicy.Action action = policy.classify(finding.anomalyType());
        if (action == RepairPolicy.Action.MANUAL_ONLY) {
            demoteToManual(finding);
            return Outcome.MANUAL_REVIEW;
        }

        InventoryReservation reservation = finding.reservationId() == null ? null
                : reservations.lock(finding.reservationId()).orElse(null);
        // The live subject must still be an ACTIVE hold; otherwise the anomaly is gone.
        if (reservation == null || reservation.status() != ReservationStatus.ACTIVE) {
            finding.markSuperseded();
            findings.save(finding);
            log.info("repair superseded (subject no longer ACTIVE) findingId={}", findingId);
            return Outcome.SUPERSEDED;
        }

        Outcome outcome = switch (action) {
            case RELEASE_CANCELLED_ORDER -> releaseCancelled(reservation, finding, actor, reason, idempotencyKey);
            case CONSUME_PAID_ORDER -> consumePaid(reservation, finding, actor, reason, idempotencyKey);
            default -> Outcome.MANUAL_REVIEW;
        };
        metrics.increment(ReservationMetrics.RECON_REPAIRED,
                "outcome", outcome.name().toLowerCase(),
                "dryRun", "false");
        return outcome;
    }

    private Outcome releaseCancelled(InventoryReservation reservation, ReconciliationFinding finding, UUID actor,
            String reason, String idempotencyKey) {
        UUID orderId = reservation.orderId();
        OrderSnapshot order = orderId == null ? null : queries.orderSnapshot(orderId);
        // Revalidate under the reservation lock: only a genuinely cancelled order's
        // hold may be released by a repair.
        if (order == null || !"CANCELLED".equals(order.status())) {
            demoteToManual(finding);
            return Outcome.MANUAL_REVIEW;
        }
        boolean released = finalization.releaseOrder(orderId, ReservationReasons.DATA_RECOVERY, actor);
        if (!released) {
            finding.markSuperseded();
            findings.save(finding);
            return Outcome.SUPERSEDED;
        }
        recordRepair(finding, actor, reason, idempotencyKey);
        return Outcome.REPAIRED;
    }

    private Outcome consumePaid(InventoryReservation reservation, ReconciliationFinding finding, UUID actor,
            String reason, String idempotencyKey) {
        UUID orderId = reservation.orderId();
        // Reuse the guarded §9 recovery: it re-checks every condition under the lock.
        PaidOrderRecoveryService.Decision decision = recovery.recover(orderId, actor);
        return switch (decision) {
            case CONSUMED -> {
                recordRepair(finding, actor, reason, idempotencyKey);
                yield Outcome.REPAIRED;
            }
            case ALREADY_CONSUMED -> {
                finding.markSuperseded();
                findings.save(finding);
                yield Outcome.NO_CHANGE;
            }
            case MANUAL_REVIEW -> {
                demoteToManual(finding);
                yield Outcome.MANUAL_REVIEW;
            }
        };
    }

    private void recordRepair(ReconciliationFinding finding, UUID actor, String reason, String idempotencyKey) {
        finding.markRepaired(actor, reason == null || reason.isBlank() ? "automatic reconciliation repair" : reason,
                idempotencyKey, java.time.Instant.now());
        findings.save(finding);
        String action = "INVENTORY_RESERVATION_REPAIR";
        if (actor != null) {
            audit.success(actor, finding.storeId(), action, "INVENTORY", "RECONCILIATION_FINDING", finding.id(), null,
                    finding.status().name(), reason);
        } else {
            audit.system(finding.storeId(), action, "INVENTORY", "RECONCILIATION_FINDING", finding.id(), null,
                    finding.status().name(), "automatic reconciliation repair");
        }
        log.info("reconciliation repair applied findingId={} anomaly={} actor={} runId={}", finding.id(),
                finding.anomalyType(), actor, finding.runId());
    }

    private void demoteToManual(ReconciliationFinding finding) {
        finding.markManualReview();
        findings.save(finding);
        metrics.increment(ReservationMetrics.RECON_FAILED, "reason", "manual_review");
    }

    /** Payment settlement verdict, exposed for the reconciliation service classification. */
    PaymentSettlement settlementOf(UUID orderId) {
        return queries.paymentSettlement(orderId);
    }
}
