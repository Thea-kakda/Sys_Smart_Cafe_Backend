-- =============================================================================
-- V032: Catalog master-data management permissions
-- =============================================================================
-- Guards the product / category write path (create/update, image assignment)
-- that stores image_object_key and lets the owning entity reference a MinIO
-- object uploaded through the File API.
--
--   product:manage   POST/PATCH /api/v1/products + management reads
--   category:manage  POST/PATCH /api/v1/categories + management reads
--
-- These are BUSINESS capabilities and are intentionally separate from
-- file:upload (a storage capability). Holding them does not grant arbitrary
-- bucket/key access: object keys are validated against the sandboxed prefixes.
--
-- Deterministic UUIDs use a fresh c300... namespace (c2000 taken by V031,
-- d000 by V017). Idempotent via ON CONFLICT.
--
-- Grants:
--   SUPER_ADMIN, ADMIN  → every permission (cross-join covers both new codes).
--   STORE_MANAGER       → both (branch catalog management), per the approved
--                         business rule; managers already hold file:upload.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('c3000000-0000-0000-0000-000000000001', 'product:manage'),
    ('c3000000-0000-0000-0000-000000000002', 'category:manage')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER gains both catalog-management codes.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code IN ('product:manage', 'category:manage')
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
