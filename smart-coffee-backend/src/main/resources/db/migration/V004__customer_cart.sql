CREATE SCHEMA IF NOT EXISTS cart;

CREATE TABLE cart.carts (
    cart_id uuid PRIMARY KEY,
    customer_id uuid REFERENCES customer.customer_profiles (customer_id),
    created_by_user_id uuid REFERENCES identity.users (user_id),
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    source varchar(30) NOT NULL,
    status varchar(30) NOT NULL,
    fulfillment_type varchar(30),
    currency_code char(30) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_cart_source CHECK (
        source IN ('CUSTOMER_WEB', 'POS')
    ),
    CONSTRAINT ck_cart_status CHECK (
        status IN (
            'ACTIVE',
            'CHECKED_OUT',
            'EXPIRED',
            'ABANDONED'
        )
    ),
    CONSTRAINT ck_cart_fulfillment CHECK (
        fulfillment_type IS NULL
        OR fulfillment_type IN (
            'DINE_IN',
            'PICKUP',
            'DELIVERY'
        )
    ),
    CONSTRAINT ck_cart_expiry CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX ux_active_customer_store_cart ON cart.carts (customer_id, store_id)
WHERE
    status = 'ACTIVE'
    AND customer_id IS NOT NULL;

CREATE INDEX ix_cart_expiration ON cart.carts (expires_at)
WHERE
    status = 'ACTIVE';

CREATE INDEX ix_cart_customer_status ON cart.carts (
    customer_id,
    status,
    updated_at DESC
);

CREATE TABLE cart.cart_items (
    cart_item_id uuid PRIMARY KEY,
    cart_id uuid NOT NULL REFERENCES cart.carts (cart_id) ON DELETE CASCADE,
    product_id uuid NOT NULL REFERENCES catalog.products (product_id),
    product_code_snapshot varchar(50) NOT NULL,
    product_name_snapshot varchar(150) NOT NULL,
    quantity integer NOT NULL,
    unit_base_price_snapshot numeric(14, 4) NOT NULL,
    note varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_cart_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_cart_item_price CHECK (unit_base_price_snapshot >= 0)
);

CREATE INDEX ix_cart_item_cart ON cart.cart_items (cart_id);

CREATE TABLE cart.cart_item_customizations (
    cart_item_customization_id uuid PRIMARY KEY,
    cart_item_id uuid NOT NULL REFERENCES cart.cart_items (cart_item_id) ON DELETE CASCADE,
    group_id uuid NOT NULL REFERENCES catalog.customization_groups (group_id),
    option_id uuid NOT NULL REFERENCES catalog.customization_options (option_id),
    group_name_snapshot varchar(100) NOT NULL,
    option_name_snapshot varchar(100) NOT NULL,
    additional_price_snapshot numeric(14, 4) NOT NULL,
    quantity integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_cart_customization_price CHECK (
        additional_price_snapshot >= 0
    ),
    CONSTRAINT ck_cart_customization_quantity CHECK (quantity > 0),
    UNIQUE (cart_item_id, option_id)
);

CREATE INDEX ix_cart_customization_item ON cart.cart_item_customizations (cart_item_id);