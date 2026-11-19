package com.smartcoffee.modules.ordering.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.ordering.application.OrderAdminQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Protected Orders Administration API (Master Prompt §8). Read-only search under
 * {@code /api/v1/management/orders}, guarded by the dedicated management permission
 * {@code order:admin-read} (V025 — deliberately NOT the operational {@code order:read})
 * and scoped per-actor by the service (store users → their stores; global
 * admins → one or all stores). No status mutation is exposed here: administrative
 * state changes go through the explicit domain commands on OrderCommandsController
 * (POST cancel/complete under the same /api/v1/management/orders namespace, guarded
 * by order:admin-write) — there is no generic status PATCH. The customer-facing
 * {@code /api/v1/orders} contract is unchanged.
 */
@Tag(name = "Orders Administration", description = "Read-only management search under /api/v1/management/orders")
@RestController
@RequestMapping("/api/v1/management/orders")
public class OrderManagementController {

    private final OrderAdminQueryService service;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;

    public OrderManagementController(OrderAdminQueryService service, CurrentActorProvider actors,
            PermissionEvaluator permissions) {
        this.service = service;
        this.actors = actors;
        this.permissions = permissions;
    }

    @Operation(summary = "Search orders (administration)",
            description = "Read-only paginated order search. Authentication: bearer access token. Permission: "
                    + "order:admin-read (dedicated management permission, deliberately NOT the operational "
                    + "order:read). Store scope is enforced server-side: store-scoped users see only their "
                    + "assigned stores; global administrators may scope to one store or all. Filters: orderNumber "
                    + "(fragment), status (AWAITING_PAYMENT|CONFIRMED|PREPARING|READY|COMPLETED|CANCELLED), "
                    + "paymentStatus (PENDING|PAID|FAILED|REFUNDED), source (CUSTOMER_WEB|POS), fulfillmentType "
                    + "(DINE_IN|PICKUP|DELIVERY), orderedFrom/orderedTo (ISO instants), customerId, storeId. "
                    + "Sort whitelist: orderedAt (default), totalAmount, status, orderNumber — DESC with stable "
                    + "orderId tiebreak; unsupported values fall back to orderedAt. Page size is bounded. "
                    + "Response: ApiResponse<PaginatedResponse<ManagementOrderSummary>>; summaries carry no "
                    + "customer personal data.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — malformed parameter (e.g. non-UUID storeId, unparsable instant)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks order:admin-read")
    })
    @GetMapping
    public ApiResponse<PaginatedResponse<ManagementOrderSummary>> search(
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String orderNumber,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String fulfillmentType,
            @RequestParam(required = false) Instant orderedFrom,
            @RequestParam(required = false) Instant orderedTo,
            @RequestParam(defaultValue = "orderedAt") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.ORDER_ADMIN_READ);
        Page<ManagementOrderSummary> result = service.search(actor, storeId, orderNumber, customerId, status,
                paymentStatus, source, fulfillmentType, orderedFrom, orderedTo, sort, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Read one order with line items (administration)",
            description = "Full management detail: order + items (with customizations) + money breakdown. "
                    + "Authentication: bearer access token. Permission: order:admin-read. The order's store is "
                    + "re-checked against the caller's scope before returning (a store-scoped manager cannot read "
                    + "another branch's order even with a valid id — IDOR guard); cross-scope reads are 403. "
                    + "Response: ApiResponse<ManagementOrderDetail>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "STORE_ACCESS_DENIED — order belongs to a store outside the caller's scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND — unknown order id")
    })
    @GetMapping("/{orderId}")
    public ApiResponse<ManagementOrderDetail> detail(@PathVariable UUID orderId) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.ORDER_ADMIN_READ);
        return ApiResponse.ok(service.detail(actor, orderId), CorrelationContext.requestId());
    }
}
