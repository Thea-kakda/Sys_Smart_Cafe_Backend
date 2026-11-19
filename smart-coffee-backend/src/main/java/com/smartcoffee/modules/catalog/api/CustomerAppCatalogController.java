package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.modules.catalog.application.ChannelCatalogService;
import com.smartcoffee.modules.catalog.application.ChannelContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-owned CUSTOMER_APP catalog surface (Slice 3B.1, spec §2). The shared
 * {@link StoreCatalogController} cannot tell the native app apart from the web because
 * the access token carries no client/app claim, so rather than trust a forgeable header
 * or an arbitrary {@code ?channel=} selector, the app reaches its catalog through this
 * dedicated route, which is HARD-WIRED to {@link com.smartcoffee.modules.catalog.domain.Channel#CUSTOMER_APP}
 * via {@link ChannelContextResolver#resolveApp()}. It delegates to the exact same
 * {@link ChannelCatalogService} as every other surface — no separate availability logic,
 * no new source of truth — and exposes no management or POS capability. A caller cannot
 * repoint it at POS/DELIVERY; the only channel it can ever serve is the customer app.
 */
@Tag(name = "Customer App Catalog", description = "Trusted server-owned CUSTOMER_APP catalog read")
@RestController
@RequestMapping("/api/v1/app/stores/{storeId}/catalog")
public class CustomerAppCatalogController {

    private final ChannelCatalogService catalog;
    private final ChannelContextResolver channels;

    public CustomerAppCatalogController(ChannelCatalogService catalog, ChannelContextResolver channels) {
        this.catalog = catalog;
        this.channels = channels;
    }

    @Operation(summary = "List the store catalog for the trusted CUSTOMER_APP channel")
    @GetMapping
    public ApiResponse<PaginatedResponse<ProductSummaryResponse>> list(@PathVariable UUID storeId,
            @RequestParam(defaultValue = "") String search, @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean availableOnly, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ProductSummaryResponse> r = catalog.list(storeId, channels.resolveApp(), search, categoryId, availableOnly,
                page, size);
        var m = new PaginatedResponse.Pagination(r.getNumber(), r.getSize(), r.getTotalElements(), r.getTotalPages(),
                r.isFirst(), r.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(r.getContent(), m), CorrelationContext.requestId());
    }

    @Operation(summary = "Product detail for the trusted CUSTOMER_APP channel")
    @GetMapping("/products/{productId}")
    public ApiResponse<ProductDetailsResponse> details(@PathVariable UUID storeId, @PathVariable UUID productId) {
        return ApiResponse.ok(catalog.detail(storeId, channels.resolveApp(), productId),
                CorrelationContext.requestId());
    }
}
