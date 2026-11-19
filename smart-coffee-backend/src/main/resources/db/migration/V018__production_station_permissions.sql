-- =============================================================================
-- V018: Production station discovery permissions and role grants (Checkpoint 4)
-- =============================================================================
-- Guards the protected management endpoint:
--   GET /api/v1/management/stores/{storeId}/production/stations
--
-- production:station-read    -> list ACTIVE stations for an accessible branch.
-- production:station-manage  -> additionally reveal INACTIVE stations
--                               (administrative configuration use case).
--
-- Deterministic seed UUIDs continue the d000... V017 convention with e000...
-- Idempotent by design (ON CONFLICT DO NOTHING), mirroring V008/V013/V017.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('e0000000-0000-0000-0000-000000000001', 'production:station-read'),
    ('e0000000-0000-0000-0000-000000000002', 'production:station-manage')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join keeps this complete
-- for newly seeded codes as well).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER may discover and administer stations (incl. inactive) for
-- assigned branches.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM (VALUES
    ('STORE_MANAGER', 'production:station-read'),
    ('STORE_MANAGER', 'production:station-manage')
) AS mapping (role_code, permission_code)
JOIN identity.roles r ON r.role_code = mapping.role_code
JOIN identity.permissions p ON p.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Production staff and cashiers may read the active station list so they can
-- route to and refresh their own queues; they never reveal inactive stations.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM (VALUES
    ('CASHIER', 'production:station-read'),
    ('BARISTA', 'production:station-read'),
    ('KITCHEN_STAFF', 'production:station-read'),
    ('BAKERY_STAFF', 'production:station-read')
) AS mapping (role_code, permission_code)
JOIN identity.roles r ON r.role_code = mapping.role_code
JOIN identity.permissions p ON p.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
