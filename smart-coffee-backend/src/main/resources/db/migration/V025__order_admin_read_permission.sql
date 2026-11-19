-- =============================================================================
-- V025: Orders Administration read permission (Master Prompt §8 "order:admin-read")
-- =============================================================================
-- Guards the protected management read endpoints:
--   GET /api/v1/management/orders            order:admin-read
--   GET /api/v1/management/orders/{orderId}  order:admin-read
--
-- The spec explicitly warns against reusing the operational order:read (held by
-- cashiers/store managers for branch operations) for the administrative search, so
-- this introduces a dedicated management-scope code.
--
-- Deterministic UUID uses a fresh bc000... namespace (b000 taken by V010, ba000 by
-- V024, a000 by V023, c/d/e/f by V013/V017/V018/V020). Idempotent via ON CONFLICT.
--
-- Grants: global admins (SUPER_ADMIN, ADMIN) via the standing cross-join, plus
-- STORE_MANAGER for branch-scoped administrative search (the service still enforces
-- per-store scoping, so a manager only ever sees its own branches). Cashiers do NOT
-- receive it — their order access remains operational (order:read).
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('bc000000-0000-0000-0000-000000000001', 'order:admin-read')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join covers the new code).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER gains the branch-scoped administrative search.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code = 'order:admin-read'
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
