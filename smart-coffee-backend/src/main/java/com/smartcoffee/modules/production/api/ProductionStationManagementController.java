package com.smartcoffee.modules.production.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.production.application.ProductionStationQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Protected production-station discovery (Checkpoint 4). Lives under
 * {@code /api/v1/management} so it is never swallowed by the public
 * {@code /api/v1/stores/**} security matcher. The active-only list is the
 * default; {@code includeInactive=true} is an administrative view guarded by a
 * separate permission inside the service.
 */
@RestController
@RequestMapping("/api/v1/management/stores/{storeId}/production")
public class ProductionStationManagementController {

    private final ProductionStationQueryService service;
    private final CurrentActorProvider actors;

    public ProductionStationManagementController(ProductionStationQueryService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/stations")
    public ApiResponse<List<ProductionStationResponse>> stations(@PathVariable UUID storeId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        var actor = actors.require();
        return ApiResponse.ok(service.discover(actor, storeId, includeInactive), CorrelationContext.requestId());
    }
}
