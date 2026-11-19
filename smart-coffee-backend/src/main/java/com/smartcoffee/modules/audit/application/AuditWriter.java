package com.smartcoffee.modules.audit.application;

import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.modules.audit.domain.AuditLog;
import com.smartcoffee.modules.audit.persistence.AuditLogRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditWriter {
    private final AuditLogRepository logs;
    private final AuditRedactionService redaction;

    public AuditWriter(AuditLogRepository l, AuditRedactionService r) {
        logs = l;
        redaction = r;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void success(UUID actor, UUID store, String action, String category, String aggregateType, UUID aggregate,
            Object before, Object after, String reason) {
        var c = CorrelationContext.get();
        logs.save(AuditLog.create("USER", actor, store, action, category, aggregateType, aggregate, "SUCCESS", reason,
                redaction.safe(before), redaction.safe(after), c == null ? null : c.requestId(),
                c == null ? null : c.correlationId(), "REST"));
    }

    /**
     * A system-originated action (startup bootstrap, schedulers): no user actor
     * exists, so actorType is SYSTEM and actor_user_id stays null. Same
     * in-transaction requirement as the user-facing write path.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void system(UUID store, String action, String category, String aggregateType, UUID aggregate, Object before,
            Object after, String reason) {
        var c = CorrelationContext.get();
        logs.save(AuditLog.create("SYSTEM", null, store, action, category, aggregateType, aggregate, "SUCCESS", reason,
                redaction.safe(before), redaction.safe(after), c == null ? null : c.requestId(),
                c == null ? null : c.correlationId(), "SYSTEM"));
    }

    /**
     * A refused privileged attempt (RBAC_ACCESS_DENIED, V052). Written in the
     * caller's transaction and intentionally vague: the reason stays generic so
     * the audit trail records THAT a denial happened without leaking policy
     * internals to whoever can read the audit log endpoint.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void denied(UUID actor, UUID store, String action, String category, String aggregateType, UUID aggregate,
            String reason) {
        var c = CorrelationContext.get();
        logs.save(AuditLog.create("USER", actor, store, action, category, aggregateType, aggregate, "DENIED", reason,
                null, null, c == null ? null : c.requestId(), c == null ? null : c.correlationId(), "REST"));
    }
}

