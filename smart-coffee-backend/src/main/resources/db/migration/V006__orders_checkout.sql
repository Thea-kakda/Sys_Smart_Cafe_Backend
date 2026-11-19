-- -- ============================================================
-- -- V006: Orders and Checkout
-- -- Database: PostgreSQL
-- -- ============================================================

-- CREATE SCHEMA IF NOT EXISTS ordering;

-- CREATE TABLE ordering.orders
-- (
--     order_id UUID PRIMARY KEY,

--     order_number VARCHAR(60) NOT NULL UNIQUE,

--     store_id UUID NOT NULL,

--     customer_id UUID,

--     cart_id UUID UNIQUE,

--     source VARCHAR(30) NOT NULL,

--     fulfillment_type VARCHAR(30) NOT NULL,

--     status VARCHAR(30) NOT NULL,

--     payment_status VARCHAR(30) NOT NULL,

--     currency_code CHAR(3) NOT NULL,

--     subtotal NUMERIC(14, 4) NOT NULL,

--     discount_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

--     tax_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

--     delivery_fee NUMERIC(14, 4) NOT NULL DEFAULT 0,

--     rounding_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

--     total_amount NUMERIC(14, 4) NOT NULL,

--     total_quantity INTEGER NOT NULL,

--     ordered_at TIMESTAMPTZ NOT NULL,

--     updated_at TIMESTAMPTZ NOT NULL,

--     version BIGINT NOT NULL DEFAULT 0,

--     CONSTRAINT fk_order_store
--         FOREIGN KEY (store_id)
--         REFERENCES organization.stores(store_id),

--     CONSTRAINT fk_order_customer
--         FOREIGN KEY (customer_id)
--         REFERENCES customer.customer_profiles(customer_id),

--     CONSTRAINT fk_order_cart
--         FOREIGN KEY (cart_id)
--         REFERENCES cart.carts(cart_id),

--     CONSTRAINT ck_order_source
--         CHECK (source IN ('CUSTOMER_WEB', 'POS')),

--     CONSTRAINT ck_order_fulfillment
--         CHECK (
--             fulfillment_type IN (
--                 'DINE_IN',
--                 'PICKUP',
--                 'DELIVERY'
--             )
--         ),

--     CONSTRAINT ck_order_money
--         CHECK (
--             subtotal >= 0
--             AND discount_amount >= 0
--             AND tax_amount >= 0
--             AND delivery_fee >= 0
--             AND total_amount >= 0
--         ),

--     CONSTRAINT ck_order_quantity
--         CHECK (total_quantity > 0),

--     CONSTRAINT ck_order_status
--         CHECK (
--             status IN (
--                 'AWAITING_PAYMENT',
--                 'CONFIRMED',
--                 'PREPARING',
--                 'READY',
--                 'COMPLETED',
--                 'CANCELLED'
--             )
--         ),

--     CONSTRAINT ck_order_payment_status
--         CHECK (
--             payment_status IN (
--                 'PENDING',
--                 'PAID',
--                 'FAILED',
--                 'REFUNDED'
--             )
--         )
-- );

-- CREATE INDEX ix_order_customer_time
--     ON ordering.orders(customer_id, ordered_at DESC, order_id DESC);

-- CREATE INDEX ix_order_store_status
--     ON ordering.orders(store_id, status, ordered_at);

-- CREATE TABLE ordering.order_items
-- (
--     order_item_id UUID PRIMARY KEY,

--     order_id UUID NOT NULL,

--     product_id UUID,

--     recipe_version_id UUID,

--     station_code VARCHAR(40),

--     product_code_snapshot VARCHAR(50) NOT NULL,

--     product_name_snapshot VARCHAR(150) NOT NULL,

--     quantity INTEGER NOT NULL,

--     unit_base_price NUMERIC(14, 4) NOT NULL,

--     unit_customization_price NUMERIC(14, 4) NOT NULL,

--     line_discount_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

--     line_tax_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

--     line_total NUMERIC(14, 4) NOT NULL,

--     note VARCHAR(500),

--     created_at TIMESTAMPTZ NOT NULL,

--     CONSTRAINT fk_order_item_order
--         FOREIGN KEY (order_id)
--         REFERENCES ordering.orders(order_id),

--     CONSTRAINT fk_order_item_product
--         FOREIGN KEY (product_id)
--         REFERENCES catalog.products(product_id),

--     CONSTRAINT fk_order_item_recipe
--         FOREIGN KEY (recipe_version_id)
--         REFERENCES recipe.recipe_versions(recipe_version_id),

--     CONSTRAINT ck_order_item_qty
--         CHECK (quantity > 0),

--     CONSTRAINT ck_order_item_money
--         CHECK (
--             unit_base_price >= 0
--             AND unit_customization_price >= 0
--             AND line_discount_amount >= 0
--             AND line_tax_amount >= 0
--             AND line_total >= 0
--         )
-- );

-- CREATE INDEX ix_order_item_order
--     ON ordering.order_items(order_id);

-- CREATE TABLE ordering.order_item_customizations
-- (
--     order_item_customization_id UUID PRIMARY KEY,

--     order_item_id UUID NOT NULL,

--     group_id UUID,

--     option_id UUID,

--     group_name_snapshot VARCHAR(100) NOT NULL,

--     option_name_snapshot VARCHAR(100) NOT NULL,

--     additional_price NUMERIC(14, 4) NOT NULL,

--     quantity INTEGER NOT NULL,

--     created_at TIMESTAMPTZ NOT NULL,

--     CONSTRAINT fk_order_customization_item
--         FOREIGN KEY (order_item_id)
--         REFERENCES ordering.order_items(order_item_id),

--     CONSTRAINT fk_order_customization_group
--         FOREIGN KEY (group_id)
--         REFERENCES catalog.customization_groups(group_id),

--     CONSTRAINT fk_order_customization_option
--         FOREIGN KEY (option_id)
--         REFERENCES catalog.customization_options(option_id),

--     CONSTRAINT ck_order_customization
--         CHECK (
--             additional_price >= 0
--             AND quantity > 0
--         )
-- );

-- CREATE INDEX ix_order_customization_item
--     ON ordering.order_item_customizations(order_item_id);

-- CREATE TABLE ordering.order_status_history
-- (
--     history_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

--     order_id UUID NOT NULL,

--     from_status VARCHAR(30),

--     to_status VARCHAR(30) NOT NULL,

--     changed_by_user_id UUID,

--     reason TEXT,

--     changed_at TIMESTAMPTZ NOT NULL,

--     CONSTRAINT fk_order_history_order
--         FOREIGN KEY (order_id)
--         REFERENCES ordering.orders(order_id),

--     CONSTRAINT fk_order_history_user
--         FOREIGN KEY (changed_by_user_id)
--         REFERENCES identity.users(user_id)
-- );

-- CREATE INDEX ix_order_history
--     ON ordering.order_status_history
--     (
--         order_id,
--         changed_at,
--         history_id
--     );

-- ALTER TABLE inventory.inventory_reservations
--     ADD CONSTRAINT fk_inventory_reservation_order
--     FOREIGN KEY (order_id)
--     REFERENCES ordering.orders(order_id);


-- ============================================================
-- V006: Orders and Checkout
-- Database: PostgreSQL
-- ============================================================

CREATE SCHEMA IF NOT EXISTS ordering;

CREATE TABLE ordering.orders
(
    order_id UUID PRIMARY KEY,

    order_number VARCHAR(60) NOT NULL UNIQUE,

    store_id UUID NOT NULL,

    customer_id UUID,

    cart_id UUID UNIQUE,

    source VARCHAR(30) NOT NULL,

    fulfillment_type VARCHAR(30) NOT NULL,

    status VARCHAR(30) NOT NULL,

    payment_status VARCHAR(30) NOT NULL,

    currency_code CHAR(3) NOT NULL,

    subtotal NUMERIC(14, 4) NOT NULL,

    discount_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

    tax_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

    delivery_fee NUMERIC(14, 4) NOT NULL DEFAULT 0,

    rounding_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

    total_amount NUMERIC(14, 4) NOT NULL,

    total_quantity INTEGER NOT NULL,

    ordered_at TIMESTAMPTZ NOT NULL,

    updated_at TIMESTAMPTZ NOT NULL,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_order_store
        FOREIGN KEY (store_id)
        REFERENCES organization.stores(store_id),

    CONSTRAINT fk_order_customer
        FOREIGN KEY (customer_id)
        REFERENCES customer.customer_profiles(customer_id),

    CONSTRAINT fk_order_cart
        FOREIGN KEY (cart_id)
        REFERENCES cart.carts(cart_id),

    CONSTRAINT ck_order_source
        CHECK (source IN ('CUSTOMER_WEB', 'POS')),

    CONSTRAINT ck_order_fulfillment
        CHECK (
            fulfillment_type IN (
                'DINE_IN',
                'PICKUP',
                'DELIVERY'
            )
        ),

    CONSTRAINT ck_order_money
        CHECK (
            subtotal >= 0
            AND discount_amount >= 0
            AND tax_amount >= 0
            AND delivery_fee >= 0
            AND total_amount >= 0
        ),

    CONSTRAINT ck_order_quantity
        CHECK (total_quantity > 0),

    CONSTRAINT ck_order_status
        CHECK (
            status IN (
                'AWAITING_PAYMENT',
                'CONFIRMED',
                'PREPARING',
                'READY',
                'COMPLETED',
                'CANCELLED'
            )
        ),

    CONSTRAINT ck_order_payment_status
        CHECK (
            payment_status IN (
                'PENDING',
                'PAID',
                'FAILED',
                'REFUNDED'
            )
        )
);

CREATE INDEX ix_order_customer_time
    ON ordering.orders(customer_id, ordered_at DESC, order_id DESC);

CREATE INDEX ix_order_store_status
    ON ordering.orders(store_id, status, ordered_at);

CREATE TABLE ordering.order_items
(
    order_item_id UUID PRIMARY KEY,

    order_id UUID NOT NULL,

    product_id UUID,

    recipe_version_id UUID,

    station_code VARCHAR(40),

    product_code_snapshot VARCHAR(50) NOT NULL,

    product_name_snapshot VARCHAR(150) NOT NULL,

    quantity INTEGER NOT NULL,

    unit_base_price NUMERIC(14, 4) NOT NULL,

    unit_customization_price NUMERIC(14, 4) NOT NULL,

    line_discount_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

    line_tax_amount NUMERIC(14, 4) NOT NULL DEFAULT 0,

    line_total NUMERIC(14, 4) NOT NULL,

    note VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_order_item_order
        FOREIGN KEY (order_id)
        REFERENCES ordering.orders(order_id),

    CONSTRAINT fk_order_item_product
        FOREIGN KEY (product_id)
        REFERENCES catalog.products(product_id),

    CONSTRAINT fk_order_item_recipe
        FOREIGN KEY (recipe_version_id)
        REFERENCES recipe.recipe_versions(recipe_version_id),

    CONSTRAINT ck_order_item_qty
        CHECK (quantity > 0),

    CONSTRAINT ck_order_item_money
        CHECK (
            unit_base_price >= 0
            AND unit_customization_price >= 0
            AND line_discount_amount >= 0
            AND line_tax_amount >= 0
            AND line_total >= 0
        )
);

CREATE INDEX ix_order_item_order
    ON ordering.order_items(order_id);

CREATE TABLE ordering.order_item_customizations
(
    order_item_customization_id UUID PRIMARY KEY,

    order_item_id UUID NOT NULL,

    group_id UUID,

    option_id UUID,

    group_name_snapshot VARCHAR(100) NOT NULL,

    option_name_snapshot VARCHAR(100) NOT NULL,

    additional_price NUMERIC(14, 4) NOT NULL,

    quantity INTEGER NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_order_customization_item
        FOREIGN KEY (order_item_id)
        REFERENCES ordering.order_items(order_item_id),

    CONSTRAINT fk_order_customization_group
        FOREIGN KEY (group_id)
        REFERENCES catalog.customization_groups(group_id),

    CONSTRAINT fk_order_customization_option
        FOREIGN KEY (option_id)
        REFERENCES catalog.customization_options(option_id),

    CONSTRAINT ck_order_customization
        CHECK (
            additional_price >= 0
            AND quantity > 0
        )
);

CREATE INDEX ix_order_customization_item
    ON ordering.order_item_customizations(order_item_id);

CREATE TABLE ordering.order_status_history
(
    history_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    order_id UUID NOT NULL,

    from_status VARCHAR(30),

    to_status VARCHAR(30) NOT NULL,

    changed_by_user_id UUID,

    reason TEXT,

    changed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_order_history_order
        FOREIGN KEY (order_id)
        REFERENCES ordering.orders(order_id),

    CONSTRAINT fk_order_history_user
        FOREIGN KEY (changed_by_user_id)
        REFERENCES identity.users(user_id)
);

CREATE INDEX ix_order_history
    ON ordering.order_status_history
    (
        order_id,
        changed_at,
        history_id
    );

ALTER TABLE inventory.inventory_reservations
    ADD CONSTRAINT fk_inventory_reservation_order
    FOREIGN KEY (order_id)
    REFERENCES ordering.orders(order_id);
