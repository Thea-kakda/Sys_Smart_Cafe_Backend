package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.application.ProductVariantCommandService;
import com.smartcoffee.modules.catalog.application.ProductVariantQueryService;
import com.smartcoffee.modules.catalog.api.request.CreateProductVariantRequest;
import com.smartcoffee.modules.catalog.api.request.ReorderVariantsRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateProductVariantRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected product-variant management surface. All mutations require
 * {@code variant:manage}; reads require {@code product:manage} (same as the
 * surrounding catalog editor). Lives under {@code /api/v1/management} to avoid
 * the public GET-only matcher on {@code /api/v1/products/**}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET    /api/v1/management/catalog/products/{id}/variants</li>
 *   <li>POST   /api/v1/management/catalog/products/{id}/variants</li>
 *   <li>POST   /api/v1/management/catalog/products/{id}/variants/reorder</li>
 *   <li>GET    /api/v1/management/catalog/variants/{id}</li>
 *   <li>PATCH  /api/v1/management/catalog/variants/{id}</li>
 *   <li>DELETE /api/v1/management/catalog/variants/{id}</li>
 *   <li>POST   /api/v1/management/catalog/variants/{id}/activate</li>
 *   <li>POST   /api/v1/management/catalog/variants/{id}/deactivate</li>
 *   <li>POST   /api/v1/management/catalog/variants/{id}/set-default</li>
 * </ul>
 */
@Tag(name = "Catalog variants", description = "Product variant management")
@RestController
@RequestMapping("/api/v1/management/catalog")
public class ProductVariantManagementController {

    private final ProductVariantQueryService queries;
    private final ProductVariantCommandService commands;
    private final CurrentActorProvider actors;

    public ProductVariantManagementController(ProductVariantQueryService queries,
            ProductVariantCommandService commands, CurrentActorProvider actors) {
        this.queries = queries;
        this.commands = commands;
        this.actors = actors;
    }

    @GetMapping("/products/{productId}/variants")
    public ApiResponse<List<ProductVariantResponse>> list(@PathVariable UUID productId) {
        var actor = actors.require();
        return ApiResponse.ok(queries.listForProduct(actor, productId), CorrelationContext.requestId());
    }

    @PostMapping("/products/{productId}/variants")
    public ApiResponse<ProductVariantResponse> create(@PathVariable UUID productId,
            @Valid @RequestBody CreateProductVariantRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(commands.create(actor, productId, req), CorrelationContext.requestId());
    }

    @PostMapping("/products/{productId}/variants/reorder")
    public ApiResponse<List<ProductVariantResponse>> reorder(@PathVariable UUID productId,
            @Valid @RequestBody ReorderVariantsRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(commands.reorder(actor, productId, req.variantIds()), CorrelationContext.requestId());
    }

    @GetMapping("/variants/{variantId}")
    public ApiResponse<ProductVariantResponse> get(@PathVariable UUID variantId) {
        var actor = actors.require();
        return ApiResponse.ok(queries.get(actor, variantId), CorrelationContext.requestId());
    }

    @PatchMapping("/variants/{variantId}")
    public ApiResponse<ProductVariantResponse> update(@PathVariable UUID variantId,
            @Valid @RequestBody UpdateProductVariantRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(commands.update(actor, variantId, req), CorrelationContext.requestId());
    }

    @DeleteMapping("/variants/{variantId}")
    public ApiResponse<Void> delete(@PathVariable UUID variantId) {
        var actor = actors.require();
        commands.delete(actor, variantId);
        return ApiResponse.ok(null, CorrelationContext.requestId());
    }

    @PutMapping("/variants/{variantId}/activate")
    public ApiResponse<ProductVariantResponse> activate(@PathVariable UUID variantId) {
        var actor = actors.require();
        return ApiResponse.ok(commands.setActive(actor, variantId, true), CorrelationContext.requestId());
    }

    @PutMapping("/variants/{variantId}/deactivate")
    public ApiResponse<ProductVariantResponse> deactivate(@PathVariable UUID variantId) {
        var actor = actors.require();
        return ApiResponse.ok(commands.setActive(actor, variantId, false), CorrelationContext.requestId());
    }

    @PutMapping("/variants/{variantId}/set-default")
    public ApiResponse<ProductVariantResponse> setDefault(@PathVariable UUID variantId,
            @RequestParam(required = false) Long version) {
        // version is accepted (unused) so future optimistic-locking at this
        // boundary can be enabled without a URL change; the flag flip itself
        // is safe to retry idempotently.
        var actor = actors.require();
        return ApiResponse.ok(commands.setDefault(actor, variantId), CorrelationContext.requestId());
    }
}
