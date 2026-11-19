CREATE SCHEMA IF NOT EXISTS inventory;

CREATE SCHEMA IF NOT EXISTS recipe;

CREATE TABLE inventory.units_of_measure (
    unit_id uuid PRIMARY KEY,
    unit_code varchar(20) NOT NULL UNIQUE,
    unit_name varchar(60) NOT NULL,
    unit_type varchar(20) NOT NULL,
    decimal_scale smallint NOT NULL,
    conversion_factor_to_base numeric(20, 8) NOT NULL,
    base_unit boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_unit_type CHECK (
        unit_type IN (
            'MASS',
            'VOLUME',
            'COUNT',
            'PACKAGE'
        )
    ),
    CONSTRAINT ck_unit_scale CHECK (decimal_scale BETWEEN 0 AND 6),
    CONSTRAINT ck_unit_factor CHECK (conversion_factor_to_base > 0)
);

CREATE TABLE inventory.ingredients (
    ingredient_id uuid PRIMARY KEY,
    ingredient_code varchar(50) NOT NULL UNIQUE,
    ingredient_name varchar(150) NOT NULL,
    base_unit_id uuid NOT NULL REFERENCES inventory.units_of_measure (unit_id),
    standard_cost numeric(14, 4) NOT NULL DEFAULT 0,
    track_inventory boolean NOT NULL DEFAULT true,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_ingredient_cost CHECK (standard_cost >= 0)
);

CREATE TABLE recipe.recipe_versions (
    recipe_version_id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES catalog.products (product_id),
    version_number integer NOT NULL,
    status varchar(20) NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (product_id, version_number),
    CONSTRAINT ck_recipe_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'RETIRED')
    ),
    CONSTRAINT ck_recipe_period CHECK (
        effective_to IS NULL
        OR effective_to > effective_from
    )
);

CREATE TABLE recipe.recipe_version_lines (
    recipe_version_line_id uuid PRIMARY KEY,
    recipe_version_id uuid NOT NULL REFERENCES recipe.recipe_versions (recipe_version_id),
    ingredient_id uuid NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    quantity_required_base numeric(18, 4) NOT NULL,
    wastage_percent numeric(9, 6) NOT NULL DEFAULT 0,
    UNIQUE (
        recipe_version_id,
        ingredient_id
    ),
    CONSTRAINT ck_recipe_quantity CHECK (quantity_required_base > 0),
    CONSTRAINT ck_recipe_waste CHECK (
        wastage_percent BETWEEN 0 AND 100
    )
);

CREATE INDEX ix_recipe_active ON recipe.recipe_versions (
    product_id,
    status,
    effective_from,
    effective_to
);

CREATE TABLE inventory.inventory_balances (
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    ingredient_id uuid NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    current_stock numeric(18, 4) NOT NULL DEFAULT 0,
    reserved_stock numeric(18, 4) NOT NULL DEFAULT 0,
    minimum_stock numeric(18, 4) NOT NULL DEFAULT 0,
    average_unit_cost numeric(14, 4) NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp,
    version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id, ingredient_id),
    CONSTRAINT ck_balance_current CHECK (current_stock >= 0),
    CONSTRAINT ck_balance_reserved CHECK (
        reserved_stock >= 0
        AND reserved_stock <= current_stock
    ),
    CONSTRAINT ck_balance_min CHECK (minimum_stock >= 0),
    CONSTRAINT ck_balance_cost CHECK (average_unit_cost >= 0)
);

CREATE TABLE inventory.inventory_reservations (
    reservation_id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    order_id uuid NOT NULL UNIQUE,
    status varchar(20) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    released_at timestamptz,
    expired_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_reservation_status CHECK (
        status IN (
            'ACTIVE',
            'CONSUMED',
            'RELEASED',
            'EXPIRED'
        )
    ),
    CONSTRAINT ck_reservation_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_reservation_expire ON inventory.inventory_reservations (expires_at)
WHERE
    status = 'ACTIVE';

CREATE TABLE inventory.inventory_reservation_lines (
    reservation_line_id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES inventory.inventory_reservations (reservation_id),
    ingredient_id uuid NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    quantity_reserved numeric(18, 4) NOT NULL,
    quantity_consumed numeric(18, 4) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    UNIQUE (reservation_id, ingredient_id),
    CONSTRAINT ck_reserved_qty CHECK (quantity_reserved > 0),
    CONSTRAINT ck_consumed_qty CHECK (
        quantity_consumed >= 0
        AND quantity_consumed <= quantity_reserved
    )
);

CREATE TABLE inventory.stock_transactions (
    stock_transaction_id uuid PRIMARY KEY,
    transaction_number varchar(80) NOT NULL UNIQUE,
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    transaction_type varchar(30) NOT NULL,
    reference_type varchar(40),
    reference_id uuid,
    recorded_by_user_id uuid REFERENCES identity.users (user_id),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_stock_type CHECK (
        transaction_type IN (
            'STOCK_IN',
            'ADJUSTMENT',
            'SALE_CONSUMPTION',
            'RETURN',
            'DAMAGED'
        )
    )
);

CREATE INDEX ix_stock_store_time ON inventory.stock_transactions (store_id, occurred_at DESC);

CREATE TABLE inventory.stock_transaction_lines (
    stock_transaction_line_id uuid PRIMARY KEY,
    stock_transaction_id uuid NOT NULL REFERENCES inventory.stock_transactions (stock_transaction_id),
    ingredient_id uuid NOT NULL REFERENCES inventory.ingredients (ingredient_id),
    quantity_delta numeric(18, 4) NOT NULL,
    unit_cost numeric(14, 4),
    balance_before numeric(18, 4) NOT NULL,
    balance_after numeric(18, 4) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_stock_delta CHECK (quantity_delta <> 0),
    CONSTRAINT ck_stock_cost CHECK (
        unit_cost IS NULL
        OR unit_cost >= 0
    ),
    CONSTRAINT ck_stock_balances CHECK (
        balance_before >= 0
        AND balance_after >= 0
    )
);

CREATE INDEX ix_stock_line_transaction ON inventory.stock_transaction_lines (stock_transaction_id);

INSERT INTO
    inventory.units_of_measure (
        unit_id,
        unit_code,
        unit_name,
        unit_type,
        decimal_scale,
        conversion_factor_to_base,
        base_unit
    )
VALUES (
        '60000000-0000-0000-0000-000000000001',
        'GRAM',
        'Gram',
        'MASS',
        4,
        1,
        true
    ),
    (
        '60000000-0000-0000-0000-000000000002',
        'KILOGRAM',
        'Kilogram',
        'MASS',
        4,
        1000,
        false
    ),
    (
        '60000000-0000-0000-0000-000000000003',
        'MILLILITER',
        'Milliliter',
        'VOLUME',
        4,
        1,
        true
    ),
    (
        '60000000-0000-0000-0000-000000000004',
        'LITER',
        'Liter',
        'VOLUME',
        4,
        1000,
        false
    ),
    (
        '60000000-0000-0000-0000-000000000005',
        'PIECE',
        'Piece',
        'COUNT',
        0,
        1,
        true
    ),
    (
        '60000000-0000-0000-0000-000000000006',
        'PACK',
        'Pack',
        'PACKAGE',
        0,
        1,
        true
    ) ON CONFLICT (unit_code) DO NOTHING;

INSERT INTO
    inventory.ingredients (
        ingredient_id,
        ingredient_code,
        ingredient_name,
        base_unit_id,
        standard_cost
    )
VALUES (
        '70000000-0000-0000-0000-000000000001',
        'COFFEE_BEAN',
        'Coffee Bean',
        '60000000-0000-0000-0000-000000000001',
        0.0250
    ),
    (
        '70000000-0000-0000-0000-000000000002',
        'FRESH_MILK',
        'Fresh Milk',
        '60000000-0000-0000-0000-000000000003',
        0.0015
    ),
    (
        '70000000-0000-0000-0000-000000000003',
        'CROISSANT_UNIT',
        'Croissant Unit',
        '60000000-0000-0000-0000-000000000005',
        1.1000
    ) ON CONFLICT (ingredient_code) DO NOTHING;

INSERT INTO
    inventory.inventory_balances (
        store_id,
        ingredient_id,
        current_stock,
        reserved_stock,
        minimum_stock,
        average_unit_cost
    )
VALUES (
        '10000000-0000-0000-0000-000000000001',
        '70000000-0000-0000-0000-000000000001',
        5000,
        0,
        1000,
        0.0250
    ),
    (
        '10000000-0000-0000-0000-000000000001',
        '70000000-0000-0000-0000-000000000002',
        10000,
        0,
        2000,
        0.0015
    ),
    (
        '10000000-0000-0000-0000-000000000001',
        '70000000-0000-0000-0000-000000000003',
        100,
        0,
        20,
        1.1000
    ) ON CONFLICT (store_id, ingredient_id) DO NOTHING;