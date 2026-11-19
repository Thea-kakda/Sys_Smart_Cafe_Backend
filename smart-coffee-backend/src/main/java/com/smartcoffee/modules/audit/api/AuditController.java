package com.smartcoffee.modules.audit.api;

import com.smartcoffee.common.api.*;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.*;
import com.smartcoffee.modules.audit.application.AuditQueryService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {
    private final AuditQueryService service;
    private final CurrentActorProvider actor;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public AuditController(AuditQueryService s, CurrentActorProvider a, PermissionEvaluator p,
            StoreAuthorizationService st) {
        service = s;
        actor = a;
        permissions = p;
        stores = st;
    }

    @GetMapping
    public ApiResponse<PaginatedResponse<AuditLogResponse>> search(@RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String category, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var a = actor.require();
        permissions.require(a, PermissionCodes.AUDIT_READ);
        if (storeId != null)
            stores.requireStore(a, storeId);
        Page<AuditLogResponse> r = service.search(storeId, category, from, to, page, size);
        var m = new PaginatedResponse.Pagination(r.getNumber(), r.getSize(), r.getTotalElements(), r.getTotalPages(),
                r.isFirst(), r.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(r.getContent(), m), CorrelationContext.requestId());
    }
}

