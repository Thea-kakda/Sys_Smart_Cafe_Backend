-- =============================================================================
-- V031: Object-storage file API permissions
-- =============================================================================
-- Guards the /api/v1/files/** upload/read-sign/delete contract built on top of
-- the existing MinIO object-storage service.
--
--   file:upload   multipart writes + short-lived signed-URL reads
--                 (products/, categories/, documents/)
--   file:delete   object deletion (the more sensitive mutation)
--
-- The bucket and the per-category object-key prefix are fixed server-side, so
-- these codes gate access to sandboxed prefixes only — never arbitrary buckets
-- or keys.
--
-- Deterministic UUIDs use a fresh c200... namespace (c1000 taken by V029,
-- bf000 by V028, d000 by V017). Idempotent via ON CONFLICT.
--
-- Grants:
--   file:upload  → SUPER_ADMIN, ADMIN (global) and STORE_MANAGER (branch media).
--   file:delete  → SUPER_ADMIN, ADMIN only (destructive; managers may upload but
--                  not delete).
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('c2000000-0000-0000-0000-000000000001', 'file:upload'),
    ('c2000000-0000-0000-0000-000000000002', 'file:delete')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join covers both new codes).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER gains upload (branch product/category media) but NOT delete.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code = 'file:upload'
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
