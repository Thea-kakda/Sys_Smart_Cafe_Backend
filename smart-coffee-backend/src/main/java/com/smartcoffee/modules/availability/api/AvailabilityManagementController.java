package com.smartcoffee.modules.availability.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.availability.AvailabilityManagementService;
import com.smartcoffee.modules.availability.ProductAvailabilityResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management-only availability preview API (Slice 3A, spec §18). A thin HTTP layer:
 * it resolves the caller and delegates to {@link AvailabilityManagementService},
 * which enforces product/catalog view, inventory view and store scope before any
 * calculation. The response is the engine's immutable, cost/supplier-free record.
 *
 * <p>Normal unavailability is NOT an error: an out-of-stock or manually sold-out
 * product returns HTTP 200 with {@code orderable=false} and machine-readable
 * reason codes (§20). HTTP errors are reserved for request/state failures that
 * prevent a valid decision (404 unknown product/variant, 400 bad quantity or
 * variant mismatch, 401/403 auth and store scope).
 */
@Tag(name = "Availability Preview", description = "Read-only product/variant/option availability preview")
@RestController
@RequestMapping("/api/v1/management/catalog")
public class AvailabilityManagementController {

    private final AvailabilityManagementService availability;
    private final CurrentActorProvider actorProvider;

    public AvailabilityManagementController(AvailabilityManagementService availability,
            CurrentActorProvider actorProvider) {
        this.availability = availability;
        this.actorProvider = actorProvider;
    }

    @Operation(summary = "Preview availability for one product at one store",
            description = "Read-only; never reserves, consumes or writes. Optional variantId narrows "
                    + "variant detail; includeOptions=false drops the option breakdown; quantity defaults to 1. "
                    + "Permissions: branch-product:read + inventory:read, with the store scope enforced.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Availability verdict (including orderable=false with reason codes)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "INVALID_AVAILABILITY_QUANTITY / VARIANT_PRODUCT_MISMATCH"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "AUTH_UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "PRODUCT_NOT_FOUND / VARIANT_NOT_FOUND")
    })
    @GetMapping("/stores/{storeId}/products/{productId}/availability")
    public ApiResponse<ProductAvailabilityResult> preview(
            @PathVariable UUID storeId,
            @PathVariable UUID productId,
            @RequestParam(required = false) UUID variantId,
            @RequestParam(defaultValue = "true") boolean includeOptions,
            @RequestParam(defaultValue = "1") int quantity) {
        CurrentActor actor = actorProvider.require();
        ProductAvailabilityResult result = availability.preview(actor, storeId, productId, variantId,
                includeOptions, quantity);
        return ApiResponse.ok(result, CorrelationContext.requestId());
    }
}
