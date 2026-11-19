CREATE SCHEMA IF NOT EXISTS notification;

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE notification.notifications (
    notification_id uuid PRIMARY KEY,
    source_event_id uuid,
    notification_type varchar(60) NOT NULL,
    category varchar(40) NOT NULL,
    message_key varchar(120) NOT NULL,
    template_arguments jsonb NOT NULL,
    action_url text,
    related_entity_type varchar(50),
    related_entity_id uuid,
    priority varchar(20) NOT NULL DEFAULT 'NORMAL',
    created_at timestamptz NOT NULL,
    expires_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (
        source_event_id,
        notification_type
    )
);

CREATE TABLE notification.notification_recipients (
    notification_recipient_id uuid PRIMARY KEY,
    notification_id uuid NOT NULL REFERENCES notification.notifications (notification_id),
    user_id uuid NOT NULL REFERENCES identity.users (user_id),
    locale varchar(10) NOT NULL,
    read_at timestamptz,
    archived_at timestamptz,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (notification_id, user_id)
);

CREATE INDEX ix_notification_user_time ON notification.notification_recipients (user_id, created_at DESC);

CREATE INDEX ix_notification_unread ON notification.notification_recipients (user_id)
WHERE
    read_at IS NULL
    AND archived_at IS NULL;

CREATE TABLE notification.notification_deliveries (
    notification_delivery_id uuid PRIMARY KEY,
    notification_recipient_id uuid NOT NULL REFERENCES notification.notification_recipients (notification_recipient_id),
    channel varchar(20) NOT NULL,
    status varchar(20) NOT NULL,
    provider_name varchar(60),
    provider_reference varchar(200),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    delivered_at timestamptz,
    last_error_code varchar(100),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (
        notification_recipient_id,
        channel
    ),
    CONSTRAINT ck_delivery_channel CHECK (
        channel IN (
            'IN_APP',
            'WEBSOCKET',
            'PUSH',
            'EMAIL'
        )
    ),
    CONSTRAINT ck_delivery_status CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'DELIVERED',
            'FAILED',
            'SKIPPED',
            'CANCELLED'
        )
    ),
    CONSTRAINT ck_delivery_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX ix_delivery_pending ON notification.notification_deliveries (
    status,
    next_attempt_at,
    created_at
);

CREATE TABLE notification.device_tokens (
    device_token_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity.users (user_id),
    token_hash char(64) NOT NULL UNIQUE,
    encrypted_token text NOT NULL,
    platform varchar(20) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE notification.notification_preferences (
    notification_preference_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity.users (user_id),
    category varchar(40) NOT NULL,
    channel varchar(20) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (user_id, category, channel)
);

CREATE TABLE audit.audit_logs (
    audit_log_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    actor_type varchar(20) NOT NULL,
    actor_user_id uuid REFERENCES identity.users (user_id),
    store_id uuid REFERENCES organization.stores (store_id),
    action varchar(100) NOT NULL,
    category varchar(60) NOT NULL,
    aggregate_type varchar(80),
    aggregate_id uuid,
    outcome varchar(30) NOT NULL,
    reason text,
    old_values jsonb,
    new_values jsonb,
    request_id varchar(100),
    correlation_id varchar(100),
    source varchar(40) NOT NULL
);

CREATE INDEX ix_audit_store_time ON audit.audit_logs (store_id, occurred_at DESC);

CREATE INDEX ix_audit_aggregate ON audit.audit_logs (
    aggregate_type,
    aggregate_id,
    occurred_at DESC
);

CREATE INDEX ix_audit_request ON audit.audit_logs (request_id);

INSERT INTO
    identity.permissions (
        permission_id,
        permission_code
    )
VALUES (
        'b0000000-0000-0000-0000-000000000001',
        'audit:read'
    ),
    (
        'b0000000-0000-0000-0000-000000000002',
        'notification:manage'
    ) ON CONFLICT (permission_code) DO NOTHING;