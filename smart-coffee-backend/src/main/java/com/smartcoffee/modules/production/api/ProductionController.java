package com.smartcoffee.modules.production.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.*;
import com.smartcoffee.modules.production.application.*;
import com.smartcoffee.modules.production.domain.StationType;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ProductionController {
    private final ProductionQueueQueryService queues;
    private final ProductionWorkflowService workflow;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public ProductionController(ProductionQueueQueryService q, ProductionWorkflowService w, CurrentActorProvider a,
            PermissionEvaluator p, StoreAuthorizationService s) {
        queues = q;
        workflow = w;
        actors = a;
        permissions = p;
        stores = s;
    }

    @GetMapping("/stores/{storeId}/production/{station}")
    public ApiResponse<List<ProductionTicketResponse>> queue(@PathVariable UUID storeId, @PathVariable String station) {
        var a = actors.require();
        permissions.require(a, PermissionCodes.PRODUCTION_QUEUE_READ);
        stores.requireStore(a, storeId);
        // Checkpoint 4: StationType.parse maps an invalid station to a safe 400
        // instead of leaking valueOf as an unhandled 500.
        return ApiResponse.ok(queues.queue(storeId, StationType.parse(station)),
                CorrelationContext.requestId());
    }

    @PostMapping("/production/ticket-items/{itemId}/start")
    public ApiResponse<ProductionTicketResponse> start(@PathVariable UUID itemId,
            @RequestHeader("Idempotency-Key") UUID key) {
        var a = actors.require();
        permissions.require(a, PermissionCodes.PRODUCTION_ITEM_START);
        return ApiResponse.ok(workflow.start(a, key, itemId), CorrelationContext.requestId());
    }

    @PostMapping("/production/ticket-items/{itemId}/ready")
    public ApiResponse<ProductionTicketResponse> ready(@PathVariable UUID itemId,
            @RequestHeader("Idempotency-Key") UUID key) {
        var a = actors.require();
        permissions.require(a, PermissionCodes.PRODUCTION_ITEM_READY);
        return ApiResponse.ok(workflow.ready(a, key, itemId), CorrelationContext.requestId());
    }
}

