CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.users (
    user_id UUID PRIMARY KEY,
    full_name VARCHAR(150) NOT NULL,
    email VARCHAR(320) NOT NULL,
    normalized_email VARCHAR(320) NOT NULL UNIQUE,
    phone VARCHAR(30),

    preferred_language VARCHAR(10) NOT NULL DEFAULT 'en-US',

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    security_version BIGINT NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_user_status
        CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED'))
);

CREATE TABLE identity.user_credentials (
    user_id UUID PRIMARY KEY,

    password_hash TEXT NOT NULL,

    failed_login_count INTEGER NOT NULL DEFAULT 0,

    locked_until TIMESTAMPTZ,

    password_changed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_user_credentials_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users(user_id)
);

CREATE TABLE identity.roles (
    role_id UUID PRIMARY KEY,

    role_code VARCHAR(60) NOT NULL UNIQUE,

    role_name VARCHAR(120) NOT NULL
);

CREATE TABLE identity.permissions (
    permission_id UUID PRIMARY KEY,

    permission_code VARCHAR(120) NOT NULL UNIQUE
);

CREATE TABLE identity.role_permissions (

    role_id UUID NOT NULL,

    permission_id UUID NOT NULL,

    PRIMARY KEY (role_id, permission_id),

    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id)
        REFERENCES identity.roles(role_id),

    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id)
        REFERENCES identity.permissions(permission_id)
);

CREATE TABLE identity.user_role_assignments (

    assignment_id UUID PRIMARY KEY,

    user_id UUID NOT NULL,

    role_id UUID NOT NULL,

    store_id UUID,

    assigned_at TIMESTAMPTZ NOT NULL,

    expires_at TIMESTAMPTZ,

    revoked_at TIMESTAMPTZ,

    CONSTRAINT fk_assignment_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users(user_id),

    CONSTRAINT fk_assignment_role
        FOREIGN KEY (role_id)
        REFERENCES identity.roles(role_id),

    CONSTRAINT ck_assignment_expiry
        CHECK (
            expires_at IS NULL
            OR expires_at > assigned_at
        )
);

CREATE INDEX ix_assignment_user_store
ON identity.user_role_assignments(user_id, store_id)
WHERE revoked_at IS NULL;

CREATE TABLE identity.auth_sessions (

    session_id UUID PRIMARY KEY,

    user_id UUID NOT NULL,

    token_family_id UUID NOT NULL,

    refresh_token_hash CHAR(64) NOT NULL UNIQUE,

    expires_at TIMESTAMPTZ NOT NULL,

    revoked_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_session_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users(user_id),

    CONSTRAINT ck_session_expiry
        CHECK (
            expires_at > created_at
        )
);

CREATE INDEX ix_session_user_active
ON identity.auth_sessions(user_id, expires_at)
WHERE revoked_at IS NULL;