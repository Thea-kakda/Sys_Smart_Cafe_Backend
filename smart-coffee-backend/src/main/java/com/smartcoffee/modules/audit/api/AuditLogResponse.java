package com.smartcoffee.modules.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(Long auditLogId, Instant occurredAt, UUID actorUserId, UUID storeId, String action,
        String category, String aggregateType, UUID aggregateId, String outcome, String reason, String requestId) {
}

