CREATE SCHEMA IF NOT EXISTS catalog;

CREATE TABLE catalog.product_categories (
    category_id uuid PRIMARY KEY,
    parent_category_id uuid REFERENCES catalog.product_categories (category_id),
    category_code varchar(50) NOT NULL UNIQUE,
    category_name varchar(100) NOT NULL,
    description text,
    image_object_key text,
    display_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_category_not_self_parent CHECK (
        parent_category_id IS NULL
        OR parent_category_id <> category_id
    ),
    CONSTRAINT ck_category_display_order CHECK (display_order >= 0)
);

CREATE INDEX ix_category_active_order ON catalog.product_categories (
    active,
    display_order,
    category_name
);

CREATE TABLE catalog.products (
    product_id uuid PRIMARY KEY,
    product_code varchar(50) NOT NULL UNIQUE,
    category_id uuid NOT NULL REFERENCES catalog.product_categories (category_id),
    product_name varchar(150) NOT NULL,
    description text,
    image_object_key text,
    base_price numeric(14, 4) NOT NULL,
    currency_code char(3) NOT NULL,
    preparation_minutes integer NOT NULL DEFAULT 0,
    tax_rate numeric(9, 6) NOT NULL DEFAULT 0,
    customizable boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_product_price CHECK (base_price >= 0),
    CONSTRAINT ck_product_prep CHECK (preparation_minutes >= 0),
    CONSTRAINT ck_product_tax CHECK (tax_rate >= 0)
);

CREATE INDEX ix_product_category_active ON catalog.products (
    category_id,
    active,
    product_name
);

CREATE INDEX ix_product_name_lower ON catalog.products (lower(product_name));

CREATE TABLE catalog.store_products (
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    product_id uuid NOT NULL REFERENCES catalog.products (product_id),
    station_code varchar(40),
    override_price numeric(14, 4),
    available boolean NOT NULL DEFAULT true,
    sold_out_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id, product_id),
    CONSTRAINT ck_store_product_price CHECK (
        override_price IS NULL
        OR override_price >= 0
    )
);

CREATE INDEX ix_store_product_available ON catalog.store_products (
    store_id,
    available,
    product_id
);

CREATE TABLE catalog.customization_groups (
    group_id uuid PRIMARY KEY,
    group_code varchar(50) NOT NULL UNIQUE,
    group_name varchar(100) NOT NULL,
    selection_type varchar(20) NOT NULL,
    minimum_selections integer NOT NULL DEFAULT 0,
    maximum_selections integer,
    display_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_customization_selection_type CHECK (
        selection_type IN ('SINGLE', 'MULTIPLE')
    ),
    CONSTRAINT ck_customization_min CHECK (minimum_selections >= 0),
    CONSTRAINT ck_customization_max CHECK (
        maximum_selections IS NULL
        OR maximum_selections >= minimum_selections
    )
);

CREATE TABLE catalog.customization_options (
    option_id uuid PRIMARY KEY,
    group_id uuid NOT NULL REFERENCES catalog.customization_groups (group_id),
    option_code varchar(50) NOT NULL,
    option_name varchar(100) NOT NULL,
    additional_price numeric(14, 4) NOT NULL DEFAULT 0,
    display_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (group_id, option_code),
    CONSTRAINT ck_option_price CHECK (additional_price >= 0)
);

CREATE INDEX ix_option_group_active ON catalog.customization_options (
    group_id,
    active,
    display_order
);

CREATE TABLE catalog.product_customization_groups (
    product_id uuid NOT NULL REFERENCES catalog.products (product_id),
    group_id uuid NOT NULL REFERENCES catalog.customization_groups (group_id),
    required boolean NOT NULL DEFAULT false,
    display_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, group_id)
);

INSERT INTO
    catalog.product_categories (
        category_id,
        category_code,
        category_name,
        description,
        display_order
    )
VALUES (
        '20000000-0000-0000-0000-000000000001',
        'COFFEE',
        'Coffee',
        'Espresso and coffee beverages',
        10
    ),
    (
        '20000000-0000-0000-0000-000000000002',
        'BAKERY',
        'Bakery',
        'Fresh bakery products',
        20
    ) ON CONFLICT (category_code) DO NOTHING;

INSERT INTO
    catalog.products (
        product_id,
        product_code,
        category_id,
        product_name,
        description,
        base_price,
        currency_code,
        preparation_minutes,
        tax_rate,
        customizable
    )
VALUES (
        '30000000-0000-0000-0000-000000000001',
        'ICED_LATTE',
        '20000000-0000-0000-0000-000000000001',
        'Iced Latte',
        'Espresso with fresh milk',
        '3.5000',
        'USD',
        8,
        '0.100000',
        true
    ),
    (
        '30000000-0000-0000-0000-000000000002',
        'CROISSANT',
        '20000000-0000-0000-0000-000000000002',
        'Butter Croissant',
        'Classic butter croissant',
        '2.2500',
        'USD',
        4,
        '0.100000',
        false
    ) ON CONFLICT (product_code) DO NOTHING;

INSERT INTO
    catalog.store_products (
        store_id,
        product_id,
        station_code,
        available
    )
VALUES (
        '10000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000001',
        'BARISTA',
        true
    ),
    (
        '10000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000002',
        'BAKERY',
        true
    ) ON CONFLICT (store_id, product_id) DO NOTHING;

INSERT INTO
    catalog.customization_groups (
        group_id,
        group_code,
        group_name,
        selection_type,
        minimum_selections,
        maximum_selections,
        display_order
    )
VALUES (
        '40000000-0000-0000-0000-000000000001',
        'MILK',
        'Milk',
        'SINGLE',
        1,
        1,
        10
    ),
    (
        '40000000-0000-0000-0000-000000000002',
        'SUGAR',
        'Sugar',
        'SINGLE',
        1,
        1,
        20
    ) ON CONFLICT (group_code) DO NOTHING;

INSERT INTO
    catalog.customization_options (
        option_id,
        group_id,
        option_code,
        option_name,
        additional_price,
        display_order
    )
VALUES (
        '50000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000001',
        'FRESH_MILK',
        'Fresh Milk',
        '0.0000',
        10
    ),
    (
        '50000000-0000-0000-0000-000000000002',
        '40000000-0000-0000-0000-000000000001',
        'OAT_MILK',
        'Oat Milk',
        '0.5000',
        20
    ),
    (
        '50000000-0000-0000-0000-000000000003',
        '40000000-0000-0000-0000-000000000002',
        'NORMAL_SUGAR',
        'Normal Sugar',
        '0.0000',
        10
    ),
    (
        '50000000-0000-0000-0000-000000000004',
        '40000000-0000-0000-0000-000000000002',
        'LESS_SUGAR',
        'Less Sugar',
        '0.0000',
        20
    ) ON CONFLICT (group_id, option_code) DO NOTHING;

INSERT INTO
    catalog.product_customization_groups (
        product_id,
        group_id,
        required,
        display_order
    )
VALUES (
        '30000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000001',
        true,
        10
    ),
    (
        '30000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000002',
        true,
        20
    ) ON CONFLICT (product_id, group_id) DO NOTHING;