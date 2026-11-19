-- =============================================================================
-- V037: Inventory management permissions (M2)
-- =============================================================================
-- Guards the M2 write paths:
--
--   inventory:transfer   stock-transfer request/dispatch/receive + reads
--   inventory:reorder    convert low-stock suggestions into a DRAFT purchase order
--   ingredient:manage    create/update/deactivate ingredient master data
--   uom:manage           create/update units of measure
--
-- Continues the e400... namespace established in V034 (…04..07). Idempotent via
-- ON CONFLICT.
--
-- Grants:
--   SUPER_ADMIN, ADMIN  → every permission (cross-join covers the four new codes).
--   STORE_MANAGER       → transfer + reorder + ingredient:manage + uom:manage.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('e4000000-0000-0000-0000-000000000004', 'inventory:transfer'),
    ('e4000000-0000-0000-0000-000000000005', 'inventory:reorder'),
    ('e4000000-0000-0000-0000-000000000006', 'ingredient:manage'),
    ('e4000000-0000-0000-0000-000000000007', 'uom:manage')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER gains the four management codes.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code IN
    ('inventory:transfer', 'inventory:reorder', 'ingredient:manage', 'uom:manage')
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
