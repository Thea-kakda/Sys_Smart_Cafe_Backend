package com.smartcoffee.modules.audit.application;

import com.smartcoffee.modules.audit.api.AuditLogResponse;
import com.smartcoffee.modules.audit.persistence.AuditLogRepository;
import java.time.*;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {
    private final AuditLogRepository logs;

    public AuditQueryService(AuditLogRepository l) {
        logs = l;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(UUID store, String category, Instant from, Instant to, int page, int size) {
        Instant safeFrom = from == null ? Instant.now().minus(Duration.ofDays(30)) : from;
        Instant safeTo = to == null ? Instant.now() : to;
        if (Duration.between(safeFrom, safeTo).toDays() > 366)
            throw new IllegalArgumentException("Audit range exceeds 366 days");
        return logs
                .search(store, category, safeFrom, safeTo,
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)))
                .map(a -> new AuditLogResponse(a.id(), a.occurredAt(), a.actorUserId(), a.storeId(), a.action(),
                        a.category(), a.aggregateType(), a.aggregateId(), a.outcome(), a.reason(), a.requestId()));
    }
}

