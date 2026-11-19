-- =============================================================================
-- V026: Promotions administration permissions (Master Prompt §9)
-- =============================================================================
-- Guards the protected promotion management endpoints:
--   GET  /api/v1/management/promotions                       promotion:read
--   GET  /api/v1/management/promotions/{promotionId}         promotion:read
--   POST /api/v1/management/promotions                       promotion:manage
--   PUT  /api/v1/management/promotions/{promotionId}         promotion:manage
--   POST /api/v1/management/promotions/{promotionId}/status  promotion:manage
--
-- The customer-facing POST /api/v1/promotions/validate is untouched and keeps
-- its current (operational) authorization.
--
-- Deterministic UUIDs use a fresh bd000... namespace (b000=V010, ba000=V024,
-- bc000=V025, a000=V023, c/d/e/f=V013/V017/V018/V020). Idempotent ON CONFLICT.
--
-- Grants: global admins (SUPER_ADMIN, ADMIN) hold both codes via the standing
-- cross-join. STORE_MANAGER gains promotion:read only — campaigns are centrally
-- authored, branch managers consume them through POS operations today.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('bd000000-0000-0000-0000-000000000001', 'promotion:read'),
    ('bd000000-0000-0000-0000-000000000002', 'promotion:manage')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join covers the new codes).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER gains read-only campaign visibility (no manage grant).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code = 'promotion:read'
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
