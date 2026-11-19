package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFinding;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFindingStatus;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationSeverity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, UUID> {

    @Query("select f from ReconciliationFinding f where f.runId=:runId order by f.severity desc, f.createdAt desc")
    Page<ReconciliationFinding> byRun(@Param("runId") UUID runId, Pageable page);

    /**
     * Open findings for one store, newest first, filtered by the caller's store
     * scope in the service layer. Backs {@code GET .../reconciliation}.
     */
    @Query("select f from ReconciliationFinding f where f.storeId=:storeId "
            + "and f.status in ('OPEN','REPAIRABLE','MANUAL_REVIEW') order by f.severity desc, f.createdAt desc")
    Page<ReconciliationFinding> openForStore(@Param("storeId") UUID storeId, Pageable page);

    @Query("select f from ReconciliationFinding f where f.status in ('OPEN','REPAIRABLE','MANUAL_REVIEW') "
            + "order by f.severity desc, f.createdAt desc")
    Page<ReconciliationFinding> openGlobal(Pageable page);

    @Query("select f from ReconciliationFinding f where f.reservationId=:reservationId "
            + "and f.status in ('OPEN','REPAIRABLE','MANUAL_REVIEW') order by f.createdAt desc")
    List<ReconciliationFinding> openForReservation(@Param("reservationId") UUID reservationId);

    /**
     * Findings of one run still awaiting an operator repair (status REPAIRABLE).
     * Backs {@code POST .../reconciliation/{runId}/repair}; a re-run naturally
     * excludes already-repaired rows because their status moved to REPAIRED.
     */
    @Query("select f from ReconciliationFinding f where f.runId=:runId and f.status='REPAIRABLE' "
            + "order by f.severity desc, f.createdAt asc")
    List<ReconciliationFinding> repairableForRun(@Param("runId") UUID runId);

    /** The most recent still-open CRITICAL/ERROR finding class for the health gate. */
    long countByStatusAndSeverityIn(ReconciliationFindingStatus status, List<ReconciliationSeverity> severities);

    long countByStatus(ReconciliationFindingStatus status);

    /** Total findings still awaiting action, across the three live statuses (gauge). */
    long countByStatusIn(java.util.Collection<ReconciliationFindingStatus> statuses);

    /** Open findings of one anomaly class — the balance-mismatch gauge (§17). */
    long countByAnomalyTypeAndStatusIn(
            com.smartcoffee.modules.inventory.domain.reconciliation.ReservationAnomalyType anomalyType,
            java.util.Collection<ReconciliationFindingStatus> statuses);

    /**
     * Availability engine safety signal (Slice 3A, spec §14): the subset of a product's
     * required ingredients that currently carry an OPEN store-scoped CRITICAL finding —
     * i.e. the inventory data behind them is known to be suspect. Bounded to the exact
     * ingredient ids the caller is about to price, so a catalog read never runs a full
     * reconciliation scan; it only asks "is any item I depend on flagged critical".
     */
    @Query("select distinct f.inventoryItemId from ReconciliationFinding f where f.storeId=:storeId "
            + "and f.severity = com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationSeverity.CRITICAL "
            + "and f.status in ('OPEN','REPAIRABLE','MANUAL_REVIEW') "
            + "and f.inventoryItemId in (:ingredients)")
    List<UUID> criticalAnomalousIngredients(@Param("storeId") UUID storeId,
            @Param("ingredients") java.util.Collection<UUID> ingredients);
}
