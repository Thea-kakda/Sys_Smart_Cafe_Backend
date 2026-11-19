-- =============================================================================
-- V015: integration.api_idempotency_records
-- =============================================================================
-- Checkpoint 2C discovery: the JPA entity ApiIdempotencyRecord
-- (infrastructure/idempotency) is exercised by real production paths
-- (OrderCheckoutService, PaymentApplicationService, ProductionWorkflowService)
-- but no prior migration ever created its table. Under the main profile
-- (ddl-auto=none) every idempotent command would fail at runtime; under the
-- test profile (ddl-auto=validate) Hibernate schema validation now fails with
-- "missing table [integration.api_idempotency_records]".
--
-- This DDL matches the entity mapping exactly:
--   * idempotency_record_id UUID PK
--   * unique command key (scope, actor_type, actor_id, idempotency_key)
--   * request_hash varchar(64) (canonical SHA-256 hex)
--   * response_body jsonb (entity binds it with @JdbcTypeCode(SqlTypes.JSON))
--   * optimistic-lock version column
-- =============================================================================

CREATE TABLE integration.api_idempotency_records (
    idempotency_record_id UUID PRIMARY KEY,

    scope VARCHAR(255) NOT NULL,
    actor_type VARCHAR(255) NOT NULL,
    actor_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,

    request_hash VARCHAR(64) NOT NULL,

    status VARCHAR(20) NOT NULL,

    response_status INTEGER,
    response_body JSONB,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_idempotency_command
        UNIQUE (scope, actor_type, actor_id, idempotency_key),

    CONSTRAINT ck_idempotency_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Supports expiry/cleanup scans without full table reads.
CREATE INDEX ix_idempotency_expires_at
    ON integration.api_idempotency_records (expires_at);
