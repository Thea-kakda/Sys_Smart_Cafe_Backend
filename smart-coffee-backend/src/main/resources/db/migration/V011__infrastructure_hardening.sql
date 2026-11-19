-- ============================================================
-- V011: Transactional Outbox and Idempotent Consumer Inbox
-- Database: PostgreSQL
-- ============================================================

CREATE SCHEMA IF NOT EXISTS integration;

-- ============================================================
-- Transactional outbox
-- ============================================================

CREATE TABLE integration.outbox_events
(
    outbox_event_id UUID PRIMARY KEY,

    event_id UUID NOT NULL,

    event_type VARCHAR(150) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,

    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,

    routing_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    occurred_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,

    attempt_count INTEGER NOT NULL DEFAULT 0,

    claimed_by VARCHAR(100),
    claimed_at TIMESTAMPTZ,

    last_error_code VARCHAR(100),
    last_error_message TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_outbox_event_id
        UNIQUE (event_id),

    CONSTRAINT ck_outbox_event_version
        CHECK (event_version > 0),

    CONSTRAINT ck_outbox_status
        CHECK (
            status IN (
                'PENDING',
                'PROCESSING',
                'PUBLISHED',
                'FAILED',
                'DEAD'
            )
        ),

    CONSTRAINT ck_outbox_attempt_count
        CHECK (attempt_count >= 0),

    CONSTRAINT ck_outbox_published_at
        CHECK (
            status <> 'PUBLISHED'
            OR published_at IS NOT NULL
        )
);

CREATE INDEX ix_outbox_ready
    ON integration.outbox_events
    (
        status,
        available_at,
        created_at,
        outbox_event_id
    )
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX ix_outbox_claim
    ON integration.outbox_events
    (
        status,
        available_at,
        created_at
    );

CREATE INDEX ix_outbox_stale
    ON integration.outbox_events
    (
        status,
        claimed_at
    )
    WHERE status = 'PROCESSING';

CREATE INDEX ix_outbox_aggregate
    ON integration.outbox_events
    (
        aggregate_type,
        aggregate_id,
        occurred_at
    );

CREATE INDEX ix_outbox_routing_key
    ON integration.outbox_events
    (
        routing_key,
        occurred_at
    );

-- ============================================================
-- Idempotent consumer inbox
-- ============================================================

CREATE TABLE integration.consumer_inbox
(
    inbox_id UUID PRIMARY KEY,

    consumer_name VARCHAR(150) NOT NULL,

    event_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,

    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',

    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,

    attempt_count INTEGER NOT NULL DEFAULT 0,

    last_error_code VARCHAR(100),
    last_error_message TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_consumer_event_hard
        UNIQUE (consumer_name, event_id),

    CONSTRAINT ck_consumer_inbox_event_version
        CHECK (event_version > 0),

    CONSTRAINT ck_consumer_inbox_status
        CHECK (
            status IN (
                'PROCESSING',
                'PROCESSED',
                'FAILED',
                'DEAD'
            )
        ),

    CONSTRAINT ck_consumer_inbox_attempt_count
        CHECK (attempt_count >= 0),

    CONSTRAINT ck_consumer_inbox_processed_at
        CHECK (
            status <> 'PROCESSED'
            OR processed_at IS NOT NULL
        )
);

CREATE INDEX ix_consumer_inbox_event
    ON integration.consumer_inbox(event_id);

CREATE INDEX ix_consumer_inbox_status
    ON integration.consumer_inbox
    (
        consumer_name,
        status,
        received_at
    );

CREATE INDEX ix_consumer_inbox_processed
    ON integration.consumer_inbox(processed_at)
    WHERE status = 'PROCESSED';
    