package com.smartcoffee.modules.payment.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.payment.application.ReceiptQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receipt reads (Full-stack wiring §1.3). The URL lives under the ordering
 * namespace for client convenience, but the data is owned by the payment
 * module — hence this controller lives in payment.api. Access: the order's
 * owning customer, or staff with order:admin-read scoped to the order's
 * store. Empty list = order exists and is visible but has no settled
 * payment yet.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Receipts", description = "Immutable receipt snapshots for orders")
public class ReceiptController {

    private final ReceiptQueryService queries;
    private final CurrentActorProvider actors;

    public ReceiptController(ReceiptQueryService q, CurrentActorProvider a) {
        queries = q;
        actors = a;
    }

    @GetMapping("/{orderId}/receipt")
    @Operation(summary = "List receipts for an order",
            description = "Returns every receipt issued against the order's payments. The snapshot is the "
                    + "immutable document captured at settlement (money breakdown, tender, exchange-rate "
                    + "trail); it is never recomputed from current prices or rates. Available to the "
                    + "owning customer or staff holding order:admin-read within the order's store scope. "
                    + "Authentication: bearer access token. Response: ApiResponse<List<ReceiptResponse>>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Receipt list (possibly empty)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "STORE_ACCESS_DENIED — scoped staff outside the order's store"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND — order missing or not visible to this actor")
    })
    public ApiResponse<List<ReceiptResponse>> receipt(@PathVariable UUID orderId) {
        return ApiResponse.ok(queries.receipt(actors.require(), orderId), CorrelationContext.requestId());
    }
}
