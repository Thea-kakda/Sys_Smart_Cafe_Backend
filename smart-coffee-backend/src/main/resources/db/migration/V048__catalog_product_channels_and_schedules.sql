-- =============================================================================
-- V048: Channel publication + store channel schedules (Enterprise upgrade Slice 3B)
-- =============================================================================
-- Adds the controlled multi-channel publication layer that the read-only Slice 3A
-- availability engine is projected onto. This migration is PURELY ADDITIVE:
--   * it never edits V001..V047 (Flyway immutability),
--   * it introduces NO new source of truth for inventory / reservations / orders,
--   * it owns channel VISIBILITY / ORDERABILITY policy and per-channel display
--     overrides + schedules ONLY. Product master data (price, active, category,
--     recipe) stays owned by catalog.products; store membership + manual sold-out
--     stay owned by catalog.store_products.
--
-- Channels (controlled enum, extensible): POS, CUSTOMER_WEB, CUSTOMER_APP are the
-- Slice 3B-integrated surfaces; QR_MENU, KIOSK, DELIVERY are declared now so the
-- constraint accepts them but remain HIDDEN (no visible publication rows are
-- backfilled) until a later slice wires them.
--
-- Publication inheritance (spec §8): legacy POS + Customer catalog behavior must be
-- preserved, so every pre-existing catalog.store_products row is backfilled with
-- explicit visible/orderable publication for POS, CUSTOMER_WEB and CUSTOMER_APP.
-- Existing products must NOT disappear after this migration. The catalog read also
-- treats an ABSENT channel row as "inherit legacy visibility" for those three
-- channels and "hidden" for the deferred three (see ChannelCatalogService), which
-- keeps products created after this migration behaving exactly as before until an
-- operator publishes them per channel.
-- =============================================================================

CREATE TABLE catalog.product_channels (
    product_channel_id    uuid            PRIMARY KEY,
    store_id              uuid            NOT NULL REFERENCES organization.stores (store_id),
    product_id            uuid            NOT NULL REFERENCES catalog.products (product_id),
    channel               varchar(20)     NOT NULL,
    visible               boolean         NOT NULL DEFAULT false,
    orderable             boolean         NOT NULL DEFAULT false,
    display_name          varchar(150),
    display_description   text,
    display_order         integer         NOT NULL DEFAULT 0,
    available_from        timestamptz,
    available_until       timestamptz,
    timezone              varchar(64),
    created_at            timestamptz     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            timestamptz     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by            uuid,
    updated_by            uuid,
    version               bigint          NOT NULL DEFAULT 0,
    CONSTRAINT uk_product_channel UNIQUE (store_id, product_id, channel),
    CONSTRAINT ck_product_channel_enum CHECK (
        channel IN ('POS', 'CUSTOMER_WEB', 'CUSTOMER_APP', 'QR_MENU', 'KIOSK', 'DELIVERY')
    ),
    CONSTRAINT ck_product_channel_display_order CHECK (display_order >= 0),
    CONSTRAINT ck_product_channel_window CHECK (
        available_from IS NULL
        OR available_until IS NULL
        OR available_from < available_until
    ),
    CONSTRAINT ck_product_channel_timezone CHECK (
        timezone IS NULL OR length(trim(timezone)) > 0
    )
);

-- Bounded per-store, per-channel catalog scan (visibility filter + display ordering
-- + pagination all run against this): store, channel, visible then ordering key.
CREATE INDEX ix_product_channel_catalog ON catalog.product_channels (
    store_id,
    channel,
    visible,
    display_order,
    product_id
);

CREATE TABLE catalog.product_channel_schedules (
    schedule_id          uuid         PRIMARY KEY,
    product_channel_id   uuid         NOT NULL REFERENCES catalog.product_channels (product_channel_id) ON DELETE CASCADE,
    day_of_week          smallint     NOT NULL,
    start_local_time     time         NOT NULL,
    end_local_time       time         NOT NULL,
    active               boolean      NOT NULL DEFAULT true,
    created_at           timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version              bigint       NOT NULL DEFAULT 0,
    -- ISO day-of-week: Monday = 1 .. Sunday = 7 (matches java.time.DayOfWeek).
    CONSTRAINT ck_channel_schedule_dow CHECK (day_of_week BETWEEN 1 AND 7),
    -- A zero-length window is meaningless; overnight is expressed as start > end
    -- (e.g. 22:00 -> 02:00), which is explicitly allowed.
    CONSTRAINT ck_channel_schedule_span CHECK (start_local_time <> end_local_time)
);

CREATE INDEX ix_channel_schedule_lookup ON catalog.product_channel_schedules (
    product_channel_id,
    day_of_week,
    active
);

-- -----------------------------------------------------------------------------
-- Deterministic publication backfill (spec §8 / decision "Channels").
--
-- Every store product offered today keeps appearing in POS and in the Customer
-- surfaces after migration, with the SAME effective behavior as before:
--   * visible  = true  -> listed in the channel catalog (membership unchanged),
--   * orderable = true -> the channel imposes no extra gate; the store_product
--     manual sold-out lever and the Slice 3A availability verdict still decide
--     the real orderability exactly as they do today.
-- display_order defaults to 0 so ordering falls back to the legacy product_name
-- ordering. No schedules are created (empty schedule == always available).
-- QR_MENU / KIOSK / DELIVERY are intentionally NOT backfilled -> hidden.
--
-- The surrogate key is a deterministic md5-derived UUID (name-space salted by
-- store/product/channel), so re-running would not duplicate rows; combined with
-- the unique key + ON CONFLICT DO NOTHING this is idempotent and stable.
-- -----------------------------------------------------------------------------
INSERT INTO catalog.product_channels (
    product_channel_id, store_id, product_id, channel,
    visible, orderable, display_order,
    created_at, updated_at, version
)
SELECT
    (
        substr(md5(sp.store_id || '|' || sp.product_id || '|' || ch.channel), 1, 8) || '-' ||
        substr(md5(sp.store_id || '|' || sp.product_id || '|' || ch.channel), 9, 4) || '-' ||
        substr(md5(sp.store_id || '|' || sp.product_id || '|' || ch.channel), 13, 4) || '-' ||
        substr(md5(sp.store_id || '|' || sp.product_id || '|' || ch.channel), 17, 4) || '-' ||
        substr(md5(sp.store_id || '|' || sp.product_id || '|' || ch.channel), 21, 12)
    )::uuid,
    sp.store_id,
    sp.product_id,
    ch.channel,
    true,
    true,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
FROM catalog.store_products sp
CROSS JOIN (VALUES ('POS'), ('CUSTOMER_WEB'), ('CUSTOMER_APP')) AS ch(channel)
ON CONFLICT (store_id, product_id, channel) DO NOTHING;

-- -----------------------------------------------------------------------------
-- Channel publication management permissions (spec §18). Seeded here; granted to
-- still applies). Distinct from branch-product:manage (store_product config) and
-- from product:manage (master data): these gate the per-channel publication and
-- schedule configuration only. The e6 namespace is unused by V001..V047 (e0 is
-- taken by production station, e4/e5 by inventory + reconciliation), so these
-- deterministic ids cannot collide with an existing permission_id primary key.
-- -----------------------------------------------------------------------------
INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('e6000000-0000-0000-0000-000000000001', 'product-channel:read'),
    ('e6000000-0000-0000-0000-000000000002', 'product-channel:manage')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join keeps this complete).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER may publish/configure channels for its own branches.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM (VALUES
    ('STORE_MANAGER', 'product-channel:read'),
    ('STORE_MANAGER', 'product-channel:manage')
) AS mapping (role_code, permission_code)
JOIN identity.roles r ON r.role_code = mapping.role_code
JOIN identity.permissions p ON p.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
