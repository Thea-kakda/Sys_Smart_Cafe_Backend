-- =============================================================================
-- V023: Staff Administration permissions and role grants (Master Prompt §6.7)
-- =============================================================================
-- Guards the new protected management endpoints under /api/v1/management/staff:
--   GET    /                            staff:read
--   GET    /{userId}                    staff:read
--   POST   /                            staff:create
--   PUT    /{userId}                    staff:update
--   POST   /{userId}/status             staff:status-manage
--   POST   /{userId}/role-assignments   staff:role-assign
--   POST   /{userId}/role-assignments/{assignmentId}/revoke   staff:role-assign
--
-- Deterministic UUIDs use a fresh a000... range (c/d/e/f are taken by
-- V013/V017/V018/V020). Idempotent via ON CONFLICT DO NOTHING.
--
-- Grants (Master §6.7: "only to verified administrative roles"): SUPER_ADMIN and
-- ADMIN only, via the standing cross-join that keeps global administrators
-- complete for newly seeded codes. Store-scoped staff self-service is
-- intentionally NOT granted here and remains a documented follow-up gap, so no
-- non-global role can create or re-role staff (privilege-escalation safety).
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'staff:read'),
    ('a0000000-0000-0000-0000-000000000002', 'staff:create'),
    ('a0000000-0000-0000-0000-000000000003', 'staff:update'),
    ('a0000000-0000-0000-0000-000000000004', 'staff:status-manage'),
    ('a0000000-0000-0000-0000-000000000005', 'staff:role-assign')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join covers the new codes).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;
