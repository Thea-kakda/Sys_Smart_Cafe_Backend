package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.application.AdminReservationReleaseService;
import com.smartcoffee.modules.inventory.application.InventoryReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Inventory reservation management API (Slice 2E, spec §12/§13). Thin HTTP layer:
 * it resolves the caller, delegates to the application services (which re-enforce
 * permission + store scope per §14) and wraps results in the standard envelope.
 * All endpoints live under {@code /api/v1/management} and require a bearer token.
 */
@Tag(name = "Inventory Management", description = "Reservation reconciliation, repair and administrative release")
@RestController
@RequestMapping("/api/v1/management/inventory")
public class InventoryManagementController {

    private final InventoryReconciliationService reconciliation;
    private final AdminReservationReleaseService release;
    private final CurrentActorProvider actorProvider;

    public InventoryManagementController(InventoryReconciliationService reconciliation,
            AdminReservationReleaseService release, CurrentActorProvider actorProvider) {
        this.reconciliation = reconciliation;
        this.release = release;
        this.actorProvider = actorProvider;
    }

    @Operation(summary = "Paginated open reconciliation findings",
            description = "Store-scoped by default; a null storeId requests an admin-only global page. "
                    + "Permission: reconciliation:view plus the store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED")
    })
    @GetMapping("/reconciliation")
    public ApiResponse<PaginatedResponse<ReconciliationFindingResponse>> findings(
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        CurrentActor actor = actorProvider.require();
        // anomalyType / severity are accepted for forward-compatibility; the store +
        // pagination dimensions are authoritative today (see the delivery report's
        // "unresolved limitations").
        var result = reconciliation.pageFindings(actor, storeId, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        var items = result.getContent().stream().map(ReconciliationFindingResponse::from).toList();
        return ApiResponse.ok(new PaginatedResponse<>(items, meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Run a reconciliation scan",
            description = "dryRun defaults to true; false additionally attempts the guarded automatic "
                    + "repair pass. Permission: reconciliation:run (plus reconciliation:repair when not dry).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED")
    })
    @PostMapping("/reconciliation/run")
    public ApiResponse<InventoryReconciliationService.RunResult> run(
            @RequestBody(required = false) ReconciliationRunRequest request) {
        CurrentActor actor = actorProvider.require();
        ReconciliationRunRequest body = request == null ? new ReconciliationRunRequest(null, null, null) : request;
        boolean dryRun = body.dryRun() == null || body.dryRun();
        var result = reconciliation.run(actor, body.storeId(), dryRun, body.maxItems());
        return ApiResponse.ok(result, CorrelationContext.requestId());
    }

    @Operation(summary = "Repair a previous run's still-valid findings",
            description = "Requires an explicit dryRun=false, a non-empty reason and reconciliation:repair. "
                    + "Each finding is revalidated under lock; stale findings are superseded, never applied.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "REPAIR_REQUIRES_EXPLICIT_DRY_RUN_FALSE / reason"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RECON_RUN_NOT_FOUND")
    })
    @PostMapping("/reconciliation/{runId}/repair")
    public ApiResponse<InventoryReconciliationService.RunResult> repair(@PathVariable UUID runId,
            @Valid @RequestBody RepairRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID headerKey) {
        CurrentActor actor = actorProvider.require();
        if (!Boolean.FALSE.equals(request.dryRun())) {
            throw new ValidationException("REPAIR_REQUIRES_EXPLICIT_DRY_RUN_FALSE",
                    "Repair requires an explicit dryRun=false confirmation");
        }
        UUID key = headerKey != null ? headerKey : request.idempotencyKey();
        var result = reconciliation.repairRun(actor, runId, request.reason(), key);
        return ApiResponse.ok(result, CorrelationContext.requestId());
    }

    @Operation(summary = "Administratively release one ACTIVE reservation",
            description = "Only an ACTIVE hold in the caller's store scope releases; consumed / converted / "
                    + "settled holds are refused. Permission: reservation:release plus the store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "RELEASE_REASON_REQUIRED / validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "RESERVATION_*_CANNOT_RELEASE / version conflict")
    })
    @PostMapping("/reservations/{reservationId}/release")
    public ApiResponse<AdminReservationReleaseService.ReleaseResult> releaseReservation(
            @PathVariable UUID reservationId, @Valid @RequestBody ReservationReleaseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID headerKey) {
        CurrentActor actor = actorProvider.require();
        UUID key = headerKey != null ? headerKey : request.idempotencyKey();
        var result = release.release(actor, reservationId, request.expectedVersion(), request.reason(), key);
        return ApiResponse.ok(result, CorrelationContext.requestId());
    }

    @Operation(summary = "Operational detail for one reservation",
            description = "Status, ownership, expiry, snapshot lines, related ledger ids and open anomalies. "
                    + "No customer personal data. Permission: reconciliation:view plus the store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND")
    })
    @GetMapping("/reservations/{reservationId}")
    public ApiResponse<InventoryReconciliationService.ReservationDetailView> reservationDetail(
            @PathVariable UUID reservationId) {
        CurrentActor actor = actorProvider.require();
        return ApiResponse.ok(reconciliation.reservationDetail(actor, reservationId), CorrelationContext.requestId());
    }
}
