package com.smartcoffee.modules.audit.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "audit", name = "audit_logs")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id")
    private Long id;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "actor_type", nullable = false)
    private String actorType;
    @Column(name = "actor_user_id")
    private UUID actorUserId;
    @Column(name = "store_id")
    private UUID storeId;
    @Column(nullable = false)
    private String action;
    @Column(nullable = false)
    private String category;
    @Column(name = "aggregate_type")
    private String aggregateType;
    @Column(name = "aggregate_id")
    private UUID aggregateId;
    @Column(nullable = false)
    private String outcome;
    @Column(name = "reason", columnDefinition = "text")
    private String reason;
    // old_values/new_values target Postgres jsonb. A plain String is bound by
    // the JDBC driver as varchar and rejected by the jsonb column, so these are
    // annotated as JSON to bind them as JSON documents (the values are already
    // redacted JSON strings produced by AuditRedactionService).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_values", columnDefinition = "jsonb")
    private String oldValues;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_values", columnDefinition = "jsonb")
    private String newValues;
    @Column(name = "request_id")
    private String requestId;
    @Column(name = "correlation_id")
    private String correlationId;
    @Column(nullable = false)
    private String source;

    protected AuditLog() {
    }

    public static AuditLog create(String actorType, UUID actor, UUID store, String action, String category,
            String aggregateType, UUID aggregate, String outcome, String reason, String oldValues, String newValues,
            String request, String correlation, String source) {
        var a = new AuditLog();
        a.occurredAt = Instant.now();
        a.actorType = actorType;
        a.actorUserId = actor;
        a.storeId = store;
        a.action = action;
        a.category = category;
        a.aggregateType = aggregateType;
        a.aggregateId = aggregate;
        a.outcome = outcome;
        a.reason = reason;
        a.oldValues = oldValues;
        a.newValues = newValues;
        a.requestId = request;
        a.correlationId = correlation;
        a.source = source;
        return a;
    }

    public Long id() {
        return id;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public UUID actorUserId() {
        return actorUserId;
    }

    public UUID storeId() {
        return storeId;
    }

    public String action() {
        return action;
    }

    public String category() {
        return category;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public String outcome() {
        return outcome;
    }

    public String reason() {
        return reason;
    }

    public String requestId() {
        return requestId;
    }
}

