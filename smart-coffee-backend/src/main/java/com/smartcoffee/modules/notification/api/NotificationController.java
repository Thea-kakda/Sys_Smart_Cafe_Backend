package com.smartcoffee.modules.notification.api;

import com.smartcoffee.common.api.*;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.notification.application.NotificationQueryService;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationQueryService service;
    private final CurrentActorProvider actor;

    public NotificationController(NotificationQueryService s, CurrentActorProvider a) {
        service = s;
        actor = a;
    }

    @GetMapping
    public ApiResponse<PaginatedResponse<NotificationResponse>> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<NotificationResponse> r = service.list(actor.require().userId(), page, size);
        var m = new PaginatedResponse.Pagination(r.getNumber(), r.getSize(), r.getTotalElements(), r.getTotalPages(),
                r.isFirst(), r.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(r.getContent(), m), CorrelationContext.requestId());
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> unread() {
        return ApiResponse.ok(new UnreadCountResponse(service.unread(actor.require().userId())),
                CorrelationContext.requestId());
    }

    @PatchMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable UUID id) {
        service.read(actor.require().userId(), id);
        return ApiResponse.ok(null, CorrelationContext.requestId());
    }

    @PatchMapping("/read-all")
    public ApiResponse<Void> readAll() {
        service.readAll(actor.require().userId());
        return ApiResponse.ok(null, CorrelationContext.requestId());
    }
}

