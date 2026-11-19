CREATE SCHEMA IF NOT EXISTS production;

CREATE TABLE production.production_stations (
    station_id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    station_code varchar(40) NOT NULL,
    station_name varchar(100) NOT NULL,
    station_type varchar(30) NOT NULL,
    display_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (store_id, station_code),
    CONSTRAINT ck_station_type CHECK (
        station_type IN (
            'BARISTA',
            'KITCHEN',
            'BAKERY',
            'PACKING',
            'PICKUP'
        )
    )
);

CREATE TABLE production.production_tickets (
    ticket_id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES ordering.orders (order_id),
    store_id uuid NOT NULL REFERENCES organization.stores (store_id),
    station_id uuid NOT NULL REFERENCES production.production_stations (station_id),
    ticket_number varchar(80) NOT NULL UNIQUE,
    status varchar(30) NOT NULL,
    priority varchar(20) NOT NULL,
    queued_at timestamptz NOT NULL,
    started_at timestamptz,
    ready_at timestamptz,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (order_id, station_id),
    CONSTRAINT ck_ticket_status CHECK (
        status IN (
            'PENDING',
            'PREPARING',
            'READY',
            'COMPLETED',
            'CANCELLED'
        )
    ),
    CONSTRAINT ck_ticket_priority CHECK (
        priority IN (
            'LOW',
            'NORMAL',
            'HIGH',
            'URGENT'
        )
    )
);

CREATE INDEX ix_production_queue ON production.production_tickets (
    store_id,
    station_id,
    status,
    priority DESC,
    queued_at
);

CREATE TABLE production.production_ticket_items (
    ticket_item_id uuid PRIMARY KEY,
    ticket_id uuid NOT NULL REFERENCES production.production_tickets (ticket_id),
    order_item_id uuid NOT NULL REFERENCES ordering.order_items (order_item_id),
    product_name_snapshot varchar(150) NOT NULL,
    quantity integer NOT NULL,
    customization_summary text,
    status varchar(30) NOT NULL,
    assigned_user_id uuid REFERENCES identity.users (user_id),
    started_at timestamptz,
    ready_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (ticket_id, order_item_id),
    CONSTRAINT ck_ticket_item_qty CHECK (quantity > 0),
    CONSTRAINT ck_ticket_item_status CHECK (
        status IN (
            'PENDING',
            'PREPARING',
            'READY',
            'COMPLETED',
            'CANCELLED'
        )
    )
);

CREATE INDEX ix_ticket_item_ticket ON production.production_ticket_items (ticket_id);

INSERT INTO
    production.production_stations (
        station_id,
        store_id,
        station_code,
        station_name,
        station_type,
        display_order
    )
VALUES (
        '90000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        'BARISTA',
        'Barista',
        'BARISTA',
        10
    ),
    (
        '90000000-0000-0000-0000-000000000002',
        '10000000-0000-0000-0000-000000000001',
        'KITCHEN',
        'Kitchen',
        'KITCHEN',
        20
    ),
    (
        '90000000-0000-0000-0000-000000000003',
        '10000000-0000-0000-0000-000000000001',
        'BAKERY',
        'Bakery',
        'BAKERY',
        30
    ) ON CONFLICT (store_id, station_code) DO NOTHING;

INSERT INTO
    identity.permissions (
        permission_id,
        permission_code
    )
VALUES (
        '91000000-0000-0000-0000-000000000001',
        'production:queue-read'
    ),
    (
        '91000000-0000-0000-0000-000000000002',
        'production:item-start'
    ),
    (
        '91000000-0000-0000-0000-000000000003',
        'production:item-ready'
    ) ON CONFLICT (permission_code) DO NOTHING;