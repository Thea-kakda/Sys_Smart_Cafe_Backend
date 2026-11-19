package com.smartcoffee.modules.inventory.domain.reconciliation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single consistency observation from a reconciliation run (Slice 2E). It is a
 * <b>report record only</b> — never an authority for stock. Repairing the
 * underlying problem goes through the existing guarded finalization service; this
 * row simply names what was seen, whether it is safe to fix automatically, and
 * (if it was) the reason and idempotency key of the repair that was applied.
 *
 * <p>Mapped to {@code inventory.inventory_reconciliation_findings} (V047). Both
 * {@code inventoryItemId} and {@code reservationId} are nullable: a store/item
 * balance mismatch may name no single reservation, and an orphan reservation may
 * name no inventory item.
 */
@Entity
@Table(schema = "inventory", name = "inventory_reconciliation_findings")
public class ReconciliationFinding {
    @Id
    @Column(name = "finding_id")
    private UUID id;
    @Column(name = "run_id", nullable = false)
    private UUID runId;
    /** Nullable: an orphan reservation line cannot be attributed to a store. */
    @Column(name = "store_id")
    private UUID storeId;
    @Column(name = "inventory_item_id")
    private UUID inventoryItemId;
    @Column(name = "reservation_id")
    private UUID reservationId;
    @Enumerated(EnumType.STRING)
    @Column(name = "anomaly_type", nullable = false)
    private ReservationAnomalyType anomalyType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconciliationSeverity severity;
    @Column(name = "expected_quantity", precision = 18, scale = 4)
    private BigDecimal expectedQuantity;
    @Column(name = "actual_quantity", precision = 18, scale = 4)
    private BigDecimal actualQuantity;
    @Column(nullable = false)
    private boolean repairable;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconciliationFindingStatus status;
    @Column(name = "opened_order_id")
    private UUID openedOrderId;
    @Column(name = "opened_cart_id")
    private UUID openedCartId;
    @Column(name = "repaired_at")
    private Instant repairedAt;
    @Column(name = "repaired_by")
    private UUID repairedBy;
    @Column(name = "repair_reason", length = 500)
    private String repairReason;
    @Column(name = "repair_idempotency_key", length = 200)
    private String repairIdempotencyKey;
    /** Diagnostic metadata (affected transaction ids, snapshot context). JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb")
    private String detail;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Version
    private long version;

    protected ReconciliationFinding() {
    }

    public static ReconciliationFinding open(UUID runId, UUID storeId, UUID inventoryItemId, UUID reservationId,
            ReservationAnomalyType type, BigDecimal expected, BigDecimal actual, UUID orderId, UUID cartId,
            String detail, Instant now) {
        var f = new ReconciliationFinding();
        f.id = UUID.randomUUID();
        f.runId = runId;
        f.storeId = storeId;
        f.inventoryItemId = inventoryItemId;
        f.reservationId = reservationId;
        f.anomalyType = type;
        f.severity = type.defaultSeverity();
        f.expectedQuantity = expected;
        f.actualQuantity = actual;
        f.openedOrderId = orderId;
        f.openedCartId = cartId;
        f.detail = detail;
        f.repairable = false;
        f.status = ReconciliationFindingStatus.OPEN;
        f.createdAt = now;
        return f;
    }

    /** The reconciliation judged this finding safe to fix automatically. */
    public void markRepairable() {
        this.repairable = true;
        this.status = ReconciliationFindingStatus.REPAIRABLE;
    }

    /** The reconciliation could not prove a safe fix; a human must decide. */
    public void markManualReview() {
        this.repairable = false;
        this.status = ReconciliationFindingStatus.MANUAL_REVIEW;
    }

    /** Records that a guarded repair was applied through the finalization service. */
    public void markRepaired(UUID actor, String reason, String idempotencyKey, Instant now) {
        this.repairable = true;
        this.status = ReconciliationFindingStatus.REPAIRED;
        this.repairedAt = now;
        this.repairedBy = actor;
        this.repairReason = reason;
        this.repairIdempotencyKey = idempotencyKey;
    }

    /** A later run found the same subject is no longer anomalous. */
    public void markSuperseded() {
        if (this.status == ReconciliationFindingStatus.REPAIRED) {
            return;
        }
        this.status = ReconciliationFindingStatus.SUPERSEDED;
    }

    public UUID id() {
        return id;
    }

    public UUID runId() {
        return runId;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID inventoryItemId() {
        return inventoryItemId;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public ReservationAnomalyType anomalyType() {
        return anomalyType;
    }

    public ReconciliationSeverity severity() {
        return severity;
    }

    public BigDecimal expectedQuantity() {
        return expectedQuantity;
    }

    public BigDecimal actualQuantity() {
        return actualQuantity;
    }

    public boolean repairable() {
        return repairable;
    }

    public ReconciliationFindingStatus status() {
        return status;
    }

    public UUID openedOrderId() {
        return openedOrderId;
    }

    public UUID openedCartId() {
        return openedCartId;
    }

    public Instant repairedAt() {
        return repairedAt;
    }

    public UUID repairedBy() {
        return repairedBy;
    }

    public String repairReason() {
        return repairReason;
    }

    public String repairIdempotencyKey() {
        return repairIdempotencyKey;
    }

    public String detail() {
        return detail;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
