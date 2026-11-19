-- =============================================================================
-- V017: Branch-product management permissions and role grants (Checkpoint 3A/3B)
-- =============================================================================
-- Guards the protected management endpoints:
--   GET  /api/v1/management/stores/{storeId}/store-products
--   POST /api/v1/management/stores/{storeId}/store-products
--   PUT  /api/v1/management/stores/{storeId}/store-products/{productId}/configuration
--
-- Deterministic seed UUIDs continue the c000... V013 convention with d000...
-- Idempotent by design (ON CONFLICT DO NOTHING), mirroring V002/V008/V010/V013.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('d0000000-0000-0000-0000-000000000001', 'branch-product:read'),
    ('d0000000-0000-0000-0000-000000000002', 'branch-product:manage')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join keeps this complete
-- for newly seeded codes as well).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER owns branch product configuration for assigned branches.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM (VALUES
    ('STORE_MANAGER', 'branch-product:read'),
    ('STORE_MANAGER', 'branch-product:manage')
) AS mapping (role_code, permission_code)
JOIN identity.roles r ON r.role_code = mapping.role_code
JOIN identity.permissions p ON p.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- CASHIER may view branch products (POS transparency) but never manage them.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM (VALUES
    ('CASHIER', 'branch-product:read')
) AS mapping (role_code, permission_code)
JOIN identity.roles r ON r.role_code = mapping.role_code
JOIN identity.permissions p ON p.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
