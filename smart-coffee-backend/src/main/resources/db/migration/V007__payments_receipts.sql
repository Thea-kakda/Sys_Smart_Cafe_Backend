CREATE SCHEMA IF NOT EXISTS payment;

CREATE TABLE payment.payment_methods (
    payment_method_id uuid PRIMARY KEY,
    method_code varchar(30) NOT NULL UNIQUE,
    method_name varchar(100) NOT NULL,
    method_type varchar(30) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_payment_method_type CHECK (
        method_type IN (
            'CASH',
            'KHQR',
            'CARD',
            'WALLET',
            'OTHER'
        )
    )
);

CREATE TABLE payment.payments (
    payment_id uuid PRIMARY KEY,
    payment_reference varchar(80) NOT NULL UNIQUE,
    order_id uuid NOT NULL REFERENCES ordering.orders (order_id),
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    payment_method_id uuid NOT NULL REFERENCES payment.payment_methods (payment_method_id),
    status varchar(30) NOT NULL,
    currency_code char(3) NOT NULL,
    amount numeric(14, 4) NOT NULL,
    amount_received numeric(14, 4),
    change_amount numeric(14, 4),
    provider_name varchar(80),
    provider_reference varchar(200),
    failure_code varchar(100),
    expires_at timestamptz,
    paid_at timestamptz,
    failed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_payment_status CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'PAID',
            'FAILED',
            'EXPIRED',
            'CANCELLED'
        )
    ),
    CONSTRAINT ck_payment_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_cash CHECK (
        amount_received IS NULL
        OR amount_received >= 0
    ),
    CONSTRAINT ck_payment_change CHECK (
        change_amount IS NULL
        OR change_amount >= 0
    )
);

CREATE UNIQUE INDEX ux_paid_payment_per_order ON payment.payments (order_id)
WHERE
    status = 'PAID';

CREATE UNIQUE INDEX ux_provider_reference ON payment.payments (
    provider_name,
    provider_reference
)
WHERE
    provider_reference IS NOT NULL;

CREATE INDEX ix_payment_order_time ON payment.payments (order_id, created_at DESC);

CREATE INDEX ix_payment_expiry ON payment.payments (expires_at)
WHERE
    status IN ('PENDING', 'PROCESSING');

CREATE TABLE payment.receipts (
    receipt_id uuid PRIMARY KEY,
    receipt_number varchar(80) NOT NULL UNIQUE,
    payment_id uuid NOT NULL UNIQUE REFERENCES payment.payments (payment_id),
    order_id uuid NOT NULL REFERENCES ordering.orders (order_id),
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    status varchar(20) NOT NULL,
    receipt_snapshot jsonb NOT NULL,
    object_key text,
    issued_at timestamptz,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_receipt_status CHECK (
        status IN ('PENDING', 'ISSUED', 'FAILED')
    )
);

INSERT INTO
    payment.payment_methods (
        payment_method_id,
        method_code,
        method_name,
        method_type,
        active,
        display_order
    )
VALUES (
        '80000000-0000-0000-0000-000000000001',
        'CASH',
        'Cash',
        'CASH',
        true,
        10
    ),
    (
        '80000000-0000-0000-0000-000000000002',
        'KHQR',
        'KHQR',
        'KHQR',
        true,
        20
    ) ON CONFLICT (method_code) DO NOTHING;