-- =============================================================================
-- V029: Orders Administration command (write) permission
-- =============================================================================
-- Guards the explicit administrative order commands:
--   POST /api/v1/management/orders/{orderId}/cancel    order:admin-write
--   POST /api/v1/management/orders/{orderId}/complete  order:admin-write
--
-- Deliberately separate from order:admin-read (V025): the search/detail reads
-- stay available to managers who must NOT mutate states, mirroring the
-- branch:read / branch:manage split (V013 lineage). Domain state-machine rules
-- (ORDER_STATE_CONFLICT) and per-store scoping are enforced in the service on
-- top of this permission.
--
-- Deterministic UUID uses a fresh c1000... namespace (c000 single-prefix is
-- taken by V013; bf000 was taken by V028). Idempotent via ON CONFLICT.
--
-- Grants: global admins via the standing cross-join, plus STORE_MANAGER —
-- cancelling/finishing a branch order is a branch-manager duty and the service
-- still restricts every command to the caller's assigned stores.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('c1000000-0000-0000-0000-000000000001', 'order:admin-write')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join covers the new code).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER gains the store-scoped administrative commands.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code = 'order:admin-write'
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
