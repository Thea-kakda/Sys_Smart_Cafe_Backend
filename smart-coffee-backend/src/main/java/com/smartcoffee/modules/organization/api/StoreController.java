package com.smartcoffee.modules.organization.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.modules.organization.application.StoreQueryService;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stores")
public class StoreController {
    private final StoreQueryService service;
    public StoreController(StoreQueryService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PaginatedResponse<StoreResponse>> list(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<StoreResponse> result = service.list(search, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @GetMapping("/{storeId}")
    public ApiResponse<StoreResponse> get(@PathVariable UUID storeId) {
        return ApiResponse.ok(service.get(storeId), CorrelationContext.requestId());
    }
}

