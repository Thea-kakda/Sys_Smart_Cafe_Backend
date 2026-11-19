CREATE SCHEMA IF NOT EXISTS promotion;

CREATE SCHEMA IF NOT EXISTS loyalty;

CREATE TABLE promotion.promotions (
    promotion_id uuid PRIMARY KEY,
    promotion_code varchar(50) UNIQUE,
    promotion_name varchar(150) NOT NULL,
    discount_type varchar(30) NOT NULL,
    discount_value numeric(14, 4) NOT NULL,
    minimum_order_amount numeric(14, 4) NOT NULL DEFAULT 0,
    maximum_discount_amount numeric(14, 4),
    currency_code char(3),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    status varchar(30) NOT NULL,
    usage_limit integer,
    per_customer_limit integer,
    usage_count integer NOT NULL DEFAULT 0,
    stackable boolean NOT NULL DEFAULT false,
    automatic boolean NOT NULL DEFAULT false,
    priority integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_promo_type CHECK (
        discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')
    ),
    CONSTRAINT ck_promo_status CHECK (
        status IN (
            'DRAFT',
            'ACTIVE',
            'PAUSED',
            'EXPIRED',
            'CANCELLED'
        )
    ),
    CONSTRAINT ck_promo_values CHECK (
        discount_value > 0
        AND minimum_order_amount >= 0
        AND (
            maximum_discount_amount IS NULL
            OR maximum_discount_amount >= 0
        )
    ),
    CONSTRAINT ck_promo_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_promo_limits CHECK (
        (
            usage_limit IS NULL
            OR usage_limit > 0
        )
        AND (
            per_customer_limit IS NULL
            OR per_customer_limit > 0
        )
        AND usage_count >= 0
    )
);

CREATE INDEX ix_promo_active ON promotion.promotions (
    status,
    starts_at,
    ends_at,
    priority DESC
);

CREATE TABLE promotion.promotion_products (
    promotion_id uuid REFERENCES promotion.promotions (promotion_id),
    product_id uuid REFERENCES catalog.products (product_id),
    PRIMARY KEY (promotion_id, product_id)
);

CREATE TABLE promotion.promotion_categories (
    promotion_id uuid REFERENCES promotion.promotions (promotion_id),
    category_id uuid REFERENCES catalog.product_categories (category_id),
    PRIMARY KEY (promotion_id, category_id)
);

CREATE TABLE promotion.promotion_stores (
    promotion_id uuid REFERENCES promotion.promotions (promotion_id),
    store_id uuid REFERENCES organization.stores (store_id),
    PRIMARY KEY (promotion_id, store_id)
);

CREATE TABLE promotion.promotion_redemptions (
    redemption_id uuid PRIMARY KEY,
    promotion_id uuid NOT NULL REFERENCES promotion.promotions (promotion_id),
    customer_id uuid REFERENCES customer.customer_profiles (customer_id),
    order_id uuid NOT NULL REFERENCES ordering.orders (order_id),
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    status varchar(30) NOT NULL,
    discount_amount numeric(14, 4) NOT NULL,
    currency_code char(3) NOT NULL,
    reserved_at timestamptz NOT NULL,
    expires_at timestamptz,
    redeemed_at timestamptz,
    released_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (promotion_id, order_id),
    CONSTRAINT ck_redemption_status CHECK (
        status IN (
            'RESERVED',
            'REDEEMED',
            'RELEASED',
            'EXPIRED'
        )
    ),
    CONSTRAINT ck_redemption_discount CHECK (discount_amount >= 0)
);

CREATE INDEX ix_redemption_customer ON promotion.promotion_redemptions (
    customer_id,
    promotion_id,
    status
);

CREATE INDEX ix_redemption_expiry ON promotion.promotion_redemptions (expires_at)
WHERE
    status = 'RESERVED';

CREATE TABLE loyalty.loyalty_accounts (
    loyalty_account_id uuid PRIMARY KEY,
    customer_id uuid NOT NULL UNIQUE REFERENCES customer.customer_profiles (customer_id),
    available_points bigint NOT NULL DEFAULT 0,
    reserved_points bigint NOT NULL DEFAULT 0,
    lifetime_earned_points bigint NOT NULL DEFAULT 0,
    lifetime_redeemed_points bigint NOT NULL DEFAULT 0,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    joined_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_loyalty_points CHECK (
        available_points >= 0
        AND reserved_points >= 0
        AND lifetime_earned_points >= 0
        AND lifetime_redeemed_points >= 0
    )
);

CREATE TABLE loyalty.loyalty_transactions (
    loyalty_transaction_id uuid PRIMARY KEY,
    loyalty_account_id uuid NOT NULL REFERENCES loyalty.loyalty_accounts (loyalty_account_id),
    transaction_number varchar(80) NOT NULL UNIQUE,
    transaction_type varchar(30) NOT NULL,
    points_delta bigint NOT NULL,
    balance_before bigint NOT NULL,
    balance_after bigint NOT NULL,
    order_id uuid REFERENCES ordering.orders (order_id),
    source_event_id uuid UNIQUE,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_loyalty_type CHECK (
        transaction_type IN (
            'EARN',
            'RESERVE',
            'REDEEM',
            'RELEASE',
            'ADJUST',
            'EXPIRE',
            'REVERSE'
        )
    ),
    CONSTRAINT ck_loyalty_balances CHECK (
        balance_before >= 0
        AND balance_after >= 0
    )
);

CREATE INDEX ix_loyalty_account_time ON loyalty.loyalty_transactions (
    loyalty_account_id,
    occurred_at DESC
);

INSERT INTO
    promotion.promotions (
        promotion_id,
        promotion_code,
        promotion_name,
        discount_type,
        discount_value,
        minimum_order_amount,
        currency_code,
        starts_at,
        ends_at,
        status,
        usage_limit,
        per_customer_limit,
        priority
    )
VALUES (
        'a0000000-0000-0000-0000-000000000001',
        'WELCOME10',
        'Welcome 10%',
        'PERCENTAGE',
        10,
        5,
        'USD',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP + INTERVAL '365 days',
        'ACTIVE',
        10000,
        1,
        100
    ) ON CONFLICT (promotion_code) DO NOTHING;