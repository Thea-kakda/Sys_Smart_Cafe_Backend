-- =============================================================================
-- V024: Roles & Permissions administration permissions and grants (Master §7.4)
-- =============================================================================
-- Guards the new protected management endpoints:
--   GET  /api/v1/management/roles                     role:read
--   GET  /api/v1/management/roles/{roleId}            role:read
--   GET  /api/v1/management/permissions               permission:read
--   PUT  /api/v1/management/roles/{roleId}/permissions role:permission-manage
--
-- Deterministic UUIDs use a fresh ba000... namespace. The single-letter ranges
-- are already consumed in identity.permissions (b000 by V010 audit/notification,
-- c/d/e/f by V013/V017/V018/V020, a000 by V023 staff), so this two-letter prefix
-- is guaranteed collision-free. Idempotent via ON CONFLICT DO NOTHING.
--
-- Grants: SUPER_ADMIN and ADMIN only, via the standing cross-join that keeps
-- global administrators complete for newly seeded codes. This is deliberate:
-- the ability to redefine who can do what must never be delegable to a
-- store-scoped role (privilege-escalation safety). Because SUPER_ADMIN's own
-- baseline is additionally protected at runtime (RolePermissionManagementService
-- refuses to edit it), role:permission-manage / role:read / permission:read can
-- never be stripped from every administrator — at least SUPER_ADMIN always
-- retains them, guaranteeing one functional global administrator.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('ba000000-0000-0000-0000-000000000001', 'role:read'),
    ('ba000000-0000-0000-0000-000000000002', 'role:permission-manage'),
    ('ba000000-0000-0000-0000-000000000003', 'permission:read')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join covers the new codes).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;
