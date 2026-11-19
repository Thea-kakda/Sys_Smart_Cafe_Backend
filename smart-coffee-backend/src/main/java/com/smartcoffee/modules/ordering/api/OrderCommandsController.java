package com.smartcoffee.modules.ordering.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.ordering.application.OrderCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Explicit administrative order state commands (Full-stack wiring §1.4).
 * These are the ONLY order mutations exposed to management clients — the
 * read-only OrderManagementController stays mutation-free, and there is no
 * generic status PATCH. Guards: order:admin-write + the order's store scope.
 * Retries are safe through the state machine itself (already-cancelled /
 * already-completed return 200; illegal transitions return 409
 * ORDER_STATE_CONFLICT), so no Idempotency-Key is required.
 */
@Tag(name = "Orders Administration", description = "Explicit management commands over orders")
@RestController
@RequestMapping("/api/v1/management/orders/{orderId}")
public class OrderCommandsController {

    private final OrderCommandService commands;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;

    public OrderCommandsController(OrderCommandService c, CurrentActorProvider a, PermissionEvaluator p) {
        commands = c;
        actors = a;
        permissions = p;
    }

    @Operation(summary = "Cancel an order (administration)",
            description = "Cancels any not-yet-closed order with a mandatory reason (status history + audit). "
                    + "A still-active ingredient reservation is released; refunding a PAID order is NOT part "
                    + "of this command. Authentication: bearer access token. Permission: order:admin-write "
                    + "plus the order's store scope. Already-cancelled orders replay with 200. "
                    + "Response: ApiResponse<OrderResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cancelled (or already cancelled — idempotent replay)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — blank or oversized reason"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED — lacks order:admin-write or order outside scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND — unknown order id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORDER_STATE_CONFLICT — completed orders cannot be cancelled")
    })
    @PostMapping("/cancel")
    public ApiResponse<OrderResponse> cancel(@PathVariable UUID orderId,
            @Valid @RequestBody CancelOrderRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.ORDER_ADMIN_WRITE);
        return ApiResponse.ok(commands.cancel(actor, orderId, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Complete an order (administration)",
            description = "Closes a READY order (handover). Authentication: bearer access token. Permission: "
                    + "order:admin-write plus the order's store scope. Already-completed orders replay with "
                    + "200; any other source state is 409. Response: ApiResponse<OrderResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Completed (or already completed — idempotent replay)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED — lacks order:admin-write or order outside scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND — unknown order id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORDER_STATE_CONFLICT — order is not READY")
    })
    @PostMapping("/complete")
    public ApiResponse<OrderResponse> complete(@PathVariable UUID orderId) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.ORDER_ADMIN_WRITE);
        return ApiResponse.ok(commands.complete(actor, orderId), CorrelationContext.requestId());
    }
}
