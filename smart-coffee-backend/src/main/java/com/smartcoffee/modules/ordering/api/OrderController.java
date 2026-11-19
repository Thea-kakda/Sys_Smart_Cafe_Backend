package com.smartcoffee.modules.ordering.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.cart.application.CurrentCustomerResolver;
import com.smartcoffee.modules.ordering.application.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderCheckoutService checkout;
    private final OrderQueryService queries;
    private final CurrentActorProvider actor;
    private final CurrentCustomerResolver customers;

    public OrderController(OrderCheckoutService c, OrderQueryService q, CurrentActorProvider a,
            CurrentCustomerResolver cu) {
        checkout = c;
        queries = q;
        actor = a;
        customers = cu;
    }

    @Operation(summary = "Place an order from the current cart",
            description = "Checkout of the authenticated customer's cart. Authentication: bearer access token; "
                    + "strictly self-scoped. Requires the Idempotency-Key header (replays return the original "
                    + "response). Validates catalog prices, converts the cart's persisted reservation "
                    + "snapshot into the order's hold (legacy lines are reserved once), and emits the "
                    + "OrderPlaced event atomically. Response: ApiResponse<OrderResponse> with 201.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "EMPTY_CART / PRICE_CHANGED / INSUFFICIENT_STOCK — checkout guard rejected")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> create(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CreateOrderRequest req) {
        var r = checkout.checkout(actor.require().userId(), key, req);
        return ResponseEntity.status(201).body(ApiResponse.ok(r, CorrelationContext.requestId()));
    }

    @Operation(summary = "List my orders (customer history)",
            description = "Paginated order history of the authenticated customer, newest first (Full-stack "
                    + "wiring §1.2). Authentication: bearer access token; ownership is structural — the query "
                    + "runs against the resolved customer id, there is no forgeable filter. Page size bounded "
                    + "at 50. Response: ApiResponse<PaginatedResponse<OrderResponse>> including line items.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer profile not found for this account (staff tokens)")
    })
    @GetMapping
    public ApiResponse<PaginatedResponse<OrderResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID user = actor.require().userId();
        var result = queries.list(customers.customerId(user), page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Read one of my orders",
            description = "Authentication: bearer access token; self-scoped — another customer's order id is "
                    + "indistinguishable from a missing one (404, never 403). Response: ApiResponse<OrderResponse>.")
    @GetMapping("/{id}")
    public ApiResponse<OrderResponse> get(@PathVariable UUID id) {
        UUID user = actor.require().userId();
        return ApiResponse.ok(queries.get(customers.customerId(user), id), CorrelationContext.requestId());
    }
}

