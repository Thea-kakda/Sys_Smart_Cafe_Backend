package com.smartcoffee.modules.payment.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.payment.application.ExchangeRateManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Protected exchange-rate management API (Checkpoint 10). Lives under
 * {@code /api/v1/management} like the other administrative contracts so it is
 * never swallowed by the public {@code /api/v1/stores/**} matcher. Permissions
 * (currency-rate:read / currency-rate:manage) and scope rules are enforced in
 * the service, not by URL config, keeping the authorization story identical to
 * branch and branch-product management.
 */
@Tag(name = "Exchange Rate Management", description = "Protected currency-rate administration under /api/v1/management/exchange-rates")
@RestController
@RequestMapping("/api/v1/management/exchange-rates")
public class ExchangeRateManagementController {

    private final ExchangeRateManagementService service;
    private final CurrentActorProvider actors;

    public ExchangeRateManagementController(ExchangeRateManagementService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @Operation(summary = "List exchange rates",
            description = "Paginated rate list. Authentication: bearer access token. Permission: "
                    + "currency-rate:read (enforced in the service, not by URL config). Filters: base/quote "
                    + "currency codes, scope (ALL|GLOBAL|BRANCH), storeId, active boolean. Response: "
                    + "ApiResponse<PaginatedResponse<ExchangeRateResponse>>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — INVALID_SCOPE value"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks currency-rate:read, or GLOBAL_RATE_ADMIN_ONLY for global-scope rows")
    })
    @GetMapping
    public ApiResponse<PaginatedResponse<ExchangeRateResponse>> list(
            @RequestParam(required = false) String base,
            @RequestParam(required = false) String quote,
            @RequestParam(required = false, defaultValue = "ALL") String scope,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var actor = actors.require();
        var result = service.list(actor, base, quote, scope, storeId, active, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Read one exchange rate",
            description = "Authentication: bearer access token. Permission: currency-rate:read (service-side). "
                    + "Response: ApiResponse<ExchangeRateResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks currency-rate:read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RATE_NOT_FOUND — unknown rate id")
    })
    @GetMapping("/{rateId}")
    public ApiResponse<ExchangeRateResponse> get(@PathVariable UUID rateId) {
        var actor = actors.require();
        return ApiResponse.ok(service.get(actor, rateId), CorrelationContext.requestId());
    }

    @Operation(summary = "Create an exchange rate",
            description = "Adds a rate for a validity period (CreateExchangeRateRequest, validated). "
                    + "Authentication: bearer access token. Permission: currency-rate:manage (service-side; "
                    + "global-scope rows require global administration). Overlapping validity windows for the "
                    + "same pair/scope are rejected. Audited. Response: ApiResponse<ExchangeRateResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — invalid fields or scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks currency-rate:manage, or GLOBAL_RATE_ADMIN_ONLY"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "RATE_PERIOD_OVERLAP — an active rate already covers this period")
    })
    @PostMapping
    public ApiResponse<ExchangeRateResponse> create(@Valid @RequestBody CreateExchangeRateRequest request) {
        var actor = actors.require();
        return ApiResponse.ok(service.create(actor, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Deactivate an exchange rate",
            description = "Retires a rate with a version echo (DeactivateExchangeRateRequest) — rates are never "
                    + "hard-deleted, preserving historical conversions. Authentication: bearer access token. "
                    + "Permission: currency-rate:manage (service-side). Audited. "
                    + "Response: ApiResponse<ExchangeRateResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks currency-rate:manage, or GLOBAL_RATE_ADMIN_ONLY"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RATE_NOT_FOUND — unknown rate id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — stale version echo")
    })
    @PostMapping("/{rateId}/deactivate")
    public ApiResponse<ExchangeRateResponse> deactivate(@PathVariable UUID rateId,
            @Valid @RequestBody DeactivateExchangeRateRequest request) {
        var actor = actors.require();
        return ApiResponse.ok(service.deactivate(actor, rateId, request.version()), CorrelationContext.requestId());
    }
}
