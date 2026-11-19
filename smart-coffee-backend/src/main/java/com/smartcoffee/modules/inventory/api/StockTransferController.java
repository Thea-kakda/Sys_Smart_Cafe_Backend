package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.application.StockTransferQueryService;
import com.smartcoffee.modules.inventory.application.StockTransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Inter-branch stock-transfer REST surface (M2). Thin: authenticate, resolve
 * the actor and delegate. A transfer spans two stores, so request travels
 * source/dest in the body (both scope-checked in the service); dispatch/receive/
 * cancel are addressed by transfer id. All require {@code inventory:transfer}.
 */
@Tag(name = "Inventory — Stock Transfers", description = "Inter-branch stock movements")
@RestController
public class StockTransferController {

    private final StockTransferService commands;
    private final StockTransferQueryService queries;
    private final CurrentActorProvider actor;

    public StockTransferController(StockTransferService commands, StockTransferQueryService queries,
            CurrentActorProvider actor) {
        this.commands = commands;
        this.queries = queries;
        this.actor = actor;
    }

    @Operation(summary = "Request an inter-branch stock transfer",
            description = "Creates a REQUESTED transfer. Source != dest, both in scope, lines qty > 0 / cost >= 0. "
                    + "Permission: inventory:transfer.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR / SAME_STORE / DUPLICATE_LINE"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INGREDIENT_NOT_FOUND")
    })
    @PostMapping("/api/v1/stock-transfers")
    public ApiResponse<StockTransferResponse> request(@Valid @RequestBody CreateStockTransferRequest request) {
        var x = actor.require();
        return ApiResponse.ok(commands.request(x, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Dispatch a transfer",
            description = "REQUESTED -> IN_TRANSIT: decrements the SOURCE balance through the guarded native "
                    + "update and writes one TRANSFER_OUT ledger transaction. Insufficient source stock -> 409 "
                    + "INSUFFICIENT_STOCK. Permission: inventory:transfer.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STOCK_TRANSFER_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK / INVALID_STATUS_TRANSITION")
    })
    @PostMapping("/api/v1/stock-transfers/{transferId}/dispatch")
    public ApiResponse<StockTransferResponse> dispatch(@PathVariable UUID transferId) {
        var x = actor.require();
        return ApiResponse.ok(commands.dispatch(x, transferId), CorrelationContext.requestId());
    }

    @Operation(summary = "Receive a dispatched transfer",
            description = "IN_TRANSIT -> RECEIVED: increments the DEST balance and restates its moving average to "
                    + "the shipped cost, writing one TRANSFER_IN ledger transaction. Permission: inventory:transfer.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STOCK_TRANSFER_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION")
    })
    @PostMapping("/api/v1/stock-transfers/{transferId}/receive")
    public ApiResponse<StockTransferResponse> receive(@PathVariable UUID transferId) {
        var x = actor.require();
        return ApiResponse.ok(commands.receive(x, transferId), CorrelationContext.requestId());
    }

    @Operation(summary = "Cancel a transfer",
            description = "REQUESTED -> CANCELLED (an already-dispatched transfer cannot be cancelled). "
                    + "Permission: inventory:transfer.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STOCK_TRANSFER_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "TRANSFER_IN_TRANSIT / INVALID_STATUS_TRANSITION")
    })
    @PostMapping("/api/v1/stock-transfers/{transferId}/cancel")
    public ApiResponse<StockTransferResponse> cancel(@PathVariable UUID transferId,
            @RequestParam(required = false) String reason) {
        var x = actor.require();
        return ApiResponse.ok(commands.cancel(x, transferId, reason), CorrelationContext.requestId());
    }

    @Operation(summary = "Get one stock transfer",
            description = "Detail incl. lines. Permission: inventory:transfer.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STOCK_TRANSFER_NOT_FOUND")
    })
    @GetMapping("/api/v1/stock-transfers/{transferId}")
    public ApiResponse<StockTransferResponse> get(@PathVariable UUID transferId) {
        var x = actor.require();
        return ApiResponse.ok(queries.get(x, transferId), CorrelationContext.requestId());
    }

    @Operation(summary = "List stock transfers touching a store",
            description = "Transfers where the store is source or destination, newest-first. Permission: "
                    + "inventory:transfer + store scope.")
    @GetMapping("/api/v1/stores/{storeId}/stock-transfers")
    public ApiResponse<PaginatedResponse<StockTransferResponse>> list(@PathVariable UUID storeId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var x = actor.require();
        var result = queries.list(x, storeId, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }
}
