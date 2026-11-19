package com.smartcoffee.modules.promotion.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.promotion.application.PromotionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Protected promotion administration API (Master Prompt §9). Lives under
 * /api/v1/management/promotions so it cannot collide with the preserved,
 * customer-facing POST /api/v1/promotions/validate contract. Reads require
 * promotion:read; every mutation requires promotion:manage and is audited by
 * the application service.
 */
@Tag(name = "Promotion Administration", description = "Protected campaign management under /api/v1/management/promotions")
@RestController
@RequestMapping("/api/v1/management/promotions")
public class PromotionManagementController {

    private final PromotionAdminService service;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;

    public PromotionManagementController(PromotionAdminService service, CurrentActorProvider actors,
            PermissionEvaluator permissions) {
        this.service = service;
        this.actors = actors;
        this.permissions = permissions;
    }

    @Operation(summary = "List promotions (administration)",
            description = "Paginated campaign list. Authentication: bearer access token. Permission: "
                    + "promotion:read. Filters: status (DRAFT|ACTIVE|PAUSED|EXPIRED|CANCELLED). Pagination: "
                    + "zero-based page, bounded size. No store scope — campaigns are global entities with "
                    + "optional store assignments. Response: ApiResponse<PaginatedResponse<PromotionAdminResponse>>; "
                    + "list rows omit assignment details (null) — fetch detail for the full set.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — INVALID_STATUS value"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks promotion:read")
    })
    @GetMapping
    public ApiResponse<PaginatedResponse<PromotionAdminResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PROMOTION_READ);
        Page<PromotionAdminResponse> result = service.list(status, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Read one promotion with assignments",
            description = "Authentication: bearer access token. Permission: promotion:read. "
                    + "Response: ApiResponse<PromotionAdminResponse> including store/category/product assignment id lists.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks promotion:read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PROMO_NOT_FOUND — unknown promotion id")
    })
    @GetMapping("/{promotionId}")
    public ApiResponse<PromotionAdminResponse> detail(@PathVariable UUID promotionId) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PROMOTION_READ);
        return ApiResponse.ok(service.detail(promotionId), CorrelationContext.requestId());
    }

    @Operation(summary = "Create a promotion",
            description = "Authentication: bearer access token. Permission: promotion:manage. Request: "
                    + "CreatePromotionRequest (promotionCode — unique, format-validated; promotionName; discountType "
                    + "PERCENTAGE|FIXED_AMOUNT; discountValue; minimumOrderAmount; maximumDiscountAmount; "
                    + "currencyCode; startsAt/endsAt; initial status DRAFT or ACTIVE only; usageLimit; "
                    + "perCustomerLimit; stackable; automatic; priority; store/category/product assignment id "
                    + "lists — absent means applies to everything). "
                    + "Monetary discounts require a currency. Assignment targets must exist (else 400 "
                    + "TARGET_NOT_FOUND). Audited. Response: ApiResponse<PromotionAdminResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — INVALID_PROMOTION_CODE, INVALID_PROMOTION_NAME, INVALID_DISCOUNT_TYPE, INVALID_DISCOUNT_VALUE, MISSING_CURRENCY, INVALID_PERIOD, INVALID_INITIAL_STATUS, or TARGET_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks promotion:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "PROMOTION_CODE_TAKEN — code already in use")
    })
    @PostMapping
    public ApiResponse<PromotionAdminResponse> create(@Valid @RequestBody CreatePromotionRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PROMOTION_MANAGE);
        return ApiResponse.ok(service.create(actor, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Update a promotion",
            description = "Authentication: bearer access token. Full-replacement PUT of editable fields (name, discount, period, thresholds, "
                    + "assignment lists). The request must echo the current version (optimistic locking) and "
                    + "nullable fields are cleared when sent null. Code, status, and usage_count are NOT editable "
                    + "here — status has its own transition endpoint and usage_count only moves through the "
                    + "redemption flow (mass-assignment guard). Audited. Permission: promotion:manage. "
                    + "Response: ApiResponse<PromotionAdminResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — field rules as create, or TARGET_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks promotion:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PROMO_NOT_FOUND — unknown promotion id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — stale version echo")
    })
    @PutMapping("/{promotionId}")
    public ApiResponse<PromotionAdminResponse> update(@PathVariable UUID promotionId,
            @Valid @RequestBody UpdatePromotionRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PROMOTION_MANAGE);
        return ApiResponse.ok(service.update(actor, promotionId, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Transition a promotion's status",
            description = "Authentication: bearer access token. Explicit lifecycle move (PromotionStatusRequest: target status, version echo, "
                    + "optional reason). Allowed: DRAFT→ACTIVE|CANCELLED, ACTIVE→PAUSED|CANCELLED, "
                    + "PAUSED→ACTIVE|CANCELLED; EXPIRED and CANCELLED are terminal. An expired campaign cannot be "
                    + "activated, and an exhausted campaign (usageCount ≥ usageLimit) cannot be activated. "
                    + "Audited. Permission: promotion:manage. Response: ApiResponse<PromotionAdminResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — INVALID_STATUS, INVALID_STATUS_TRANSITION, INVALID_PERIOD, or PROMOTION_EXHAUSTED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks promotion:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PROMO_NOT_FOUND — unknown promotion id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — stale version echo")
    })
    @PostMapping("/{promotionId}/status")
    public ApiResponse<PromotionAdminResponse> changeStatus(@PathVariable UUID promotionId,
            @Valid @RequestBody PromotionStatusRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PROMOTION_MANAGE);
        return ApiResponse.ok(service.changeStatus(actor, promotionId, request), CorrelationContext.requestId());
    }
}
