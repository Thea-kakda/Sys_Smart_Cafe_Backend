-- ============================================================
-- V002: Organization, Customer and Initial Role Data
-- Database: PostgreSQL
-- ============================================================

CREATE SCHEMA IF NOT EXISTS organization;
CREATE SCHEMA IF NOT EXISTS customer;

-- ============================================================
-- Organization stores
-- ============================================================

CREATE TABLE organization.stores
(
    store_id UUID PRIMARY KEY,
    store_code VARCHAR(30) NOT NULL UNIQUE,
    store_name VARCHAR(150) NOT NULL,
    address TEXT NOT NULL,
    phone VARCHAR(30),
    timezone VARCHAR(64) NOT NULL,
    default_currency CHAR(3) NOT NULL,
    default_language VARCHAR(10) NOT NULL DEFAULT 'en-US',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- user_role_assignments.store_id was created in V001.
ALTER TABLE identity.user_role_assignments
    ADD CONSTRAINT fk_user_role_assignment_store
    FOREIGN KEY (store_id)
    REFERENCES organization.stores(store_id);

CREATE UNIQUE INDEX ux_active_global_user_role
    ON identity.user_role_assignments(user_id, role_id)
    WHERE store_id IS NULL
      AND revoked_at IS NULL;

CREATE UNIQUE INDEX ux_active_store_user_role
    ON identity.user_role_assignments(user_id, role_id, store_id)
    WHERE store_id IS NOT NULL
      AND revoked_at IS NULL;

-- ============================================================
-- Customer profile
-- ============================================================

CREATE TABLE customer.customer_profiles
(
    customer_id UUID PRIMARY KEY,

    user_id UUID NOT NULL UNIQUE,

    preferred_store_id UUID,

    loyalty_number VARCHAR(40) UNIQUE,

    birth_date DATE,

    marketing_opt_in BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL,

    updated_at TIMESTAMPTZ NOT NULL,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_customer_profile_user
        FOREIGN KEY (user_id)
        REFERENCES identity.users(user_id),

    CONSTRAINT fk_customer_preferred_store
        FOREIGN KEY (preferred_store_id)
        REFERENCES organization.stores(store_id)
);

-- ============================================================
-- Initial roles
-- ============================================================

INSERT INTO identity.roles
(
    role_id,
    role_code,
    role_name
)
VALUES
    (
        '00000000-0000-0000-0000-000000000001',
        'CUSTOMER',
        'Customer'
    ),
    (
        '00000000-0000-0000-0000-000000000002',
        'SUPER_ADMIN',
        'Super Administrator'
    ),
    (
        '00000000-0000-0000-0000-000000000003',
        'ADMIN',
        'Administrator'
    ),
    (
        '00000000-0000-0000-0000-000000000004',
        'STORE_MANAGER',
        'Store Manager'
    ),
    (
        '00000000-0000-0000-0000-000000000005',
        'CASHIER',
        'Cashier'
    ),
    (
        '00000000-0000-0000-0000-000000000006',
        'BARISTA',
        'Barista'
    ),
    (
        '00000000-0000-0000-0000-000000000007',
        'KITCHEN_STAFF',
        'Kitchen Staff'
    ),
    (
        '00000000-0000-0000-0000-000000000008',
        'BAKERY_STAFF',
        'Bakery Staff'
    )
ON CONFLICT (role_code) DO NOTHING;

-- ============================================================
-- Initial store
-- ============================================================

INSERT INTO organization.stores
(
    store_id,
    store_code,
    store_name,
    address,
    phone,
    timezone,
    default_currency,
    default_language,
    active,
    created_at,
    updated_at,
    version
)
VALUES
(
    '10000000-0000-0000-0000-000000000001',
    'BKK1',
    'SMART Coffee BKK1',
    'Phnom Penh',
    NULL,
    'Asia/Phnom_Penh',
    'USD',
    'km-KH',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
)
ON CONFLICT (store_code) DO NOTHING;
