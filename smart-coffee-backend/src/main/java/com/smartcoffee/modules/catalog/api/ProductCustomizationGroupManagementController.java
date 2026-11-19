package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.application.ProductCustomizationGroupManagementService;
import com.smartcoffee.modules.catalog.api.request.ConfigureProductCustomizationGroupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected product customization-group management
 * ({@code /api/v1/management/catalog/products/{productId}/customization-groups}).
 * Sits next to the variant sub-resource on the same parent path and is guarded
 * by {@code product:manage}, the code every other management catalog read/write
 * in this module already requires.
 *
 * <p>Round 1 deliberately exposes only what
 * {@code catalog.product_customization_groups} can store. Every response
 * carries the {@code capabilities} block so the Dashboard learns which fields
 * are writable before it renders an input, and the service rejects an override
 * it cannot persist instead of quietly forgetting it.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    .../customization-groups} — effective configuration per attached group</li>
 *   <li>{@code PUT    .../customization-groups/{groupId}} — attach or reconfigure (optimistic-locked)</li>
 *   <li>{@code DELETE .../customization-groups/{groupId}} — detach the relationship only (optimistic-locked)</li>
 * </ul>
 */
@Tag(name = "Product customization groups", description = "Product/option-group attachment management")
@RestController
@RequestMapping("/api/v1/management/catalog/products/{productId}/customization-groups")
public class ProductCustomizationGroupManagementController {

    private final ProductCustomizationGroupManagementService service;
    private final CurrentActorProvider actors;

    public ProductCustomizationGroupManagementController(ProductCustomizationGroupManagementService service,
            CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @Operation(summary = "List a product's attached customization groups with their effective configuration",
            description = "Requires product:manage. minimum/maximum selections are inherited from the master "
                    + "group, defaultOptionId is always null and relationshipVersion is the owning product "
                    + "version in Round 1; the capabilities block states which fields are writable.")
    @GetMapping
    public ApiResponse<ProductCustomizationGroupsResponse> list(@PathVariable UUID productId) {
        return ApiResponse.ok(service.list(actors.require(), productId), CorrelationContext.requestId());
    }

    @Operation(summary = "Attach a customization group to a product, or reconfigure the existing attachment",
            description = "Requires product:manage. Writable in Round 1: required, displayOrder and the "
                    + "expectedProductVersion lock token. A per-product minimum/maximum override, default "
                    + "option or option subset is refused with a stable 400 naming the missing capability; "
                    + "echoing the master values back unchanged is accepted. A stale token is 409 "
                    + "PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT. Never creates a duplicate row.")
    @PutMapping("/{groupId}")
    public ApiResponse<ProductCustomizationGroupsResponse> configure(@PathVariable UUID productId,
            @PathVariable UUID groupId, @Valid @RequestBody ConfigureProductCustomizationGroupRequest request) {
        return ApiResponse.ok(service.configure(actors.require(), productId, groupId, request),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Detach a customization group from a product",
            description = "Requires product:manage and the current expectedProductVersion. Deletes only the "
                    + "relationship row: the master group, its options and every historical cart/order "
                    + "customization snapshot stay intact. A missing attachment is 404 "
                    + "PRODUCT_CUSTOMIZATION_GROUP_NOT_FOUND. Returns the authoritative new product version.")
    @DeleteMapping("/{groupId}")
    public ApiResponse<ProductCustomizationGroupsResponse> detach(@PathVariable UUID productId,
            @PathVariable UUID groupId, @RequestParam long expectedProductVersion) {
        return ApiResponse.ok(service.detach(actors.require(), productId, groupId, expectedProductVersion),
                CorrelationContext.requestId());
    }
}
