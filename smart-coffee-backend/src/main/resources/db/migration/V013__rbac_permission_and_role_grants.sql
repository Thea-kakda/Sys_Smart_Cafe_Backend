-- ============================================================
-- V013: RBAC Permission Codes and Role Grants
-- ------------------------------------------------------------
-- Purpose:
--   1. Seed permission codes that backend code references but that
--      no prior migration ever created (pos:access, order:read,
--      inventory:read, inventory:adjust).
--   2. Introduce the branch-management capability codes required by
--      Checkpoint 2 (branch:read, branch:manage).
--   3. Populate identity.role_permissions. This table was created in
--      V001 but never seeded, so every authenticated user resolved to
--      zero effective permissions and every permission-guarded
--      endpoint returned 403. Seeding it is the prerequisite for the
--      whole access-control stack to function.
--
-- Conventions preserved:
--   - Deterministic UUIDs and ON CONFLICT DO NOTHING (idempotent).
--   - Grants are expressed by role_code / permission_code joins rather
--     than hard-coded UUIDs so the mapping stays readable and resilient.
--   - Global administrators (SUPER_ADMIN, ADMIN) hold every permission
--     that exists at seed time; they resolve permissions through
--     store-NULL assignments (see PermissionQueryRepository.global and
--     CurrentActorProvider).
-- ============================================================

-- ------------------------------------------------------------
-- 1. Referenced-but-missing permission codes + branch codes
-- ------------------------------------------------------------
INSERT INTO identity.permissions (permission_id, permission_code)
VALUES
    ('c0000000-0000-0000-0000-000000000001', 'pos:access'),
    ('c0000000-0000-0000-0000-000000000002', 'order:read'),
    ('c0000000-0000-0000-0000-000000000003', 'inventory:read'),
    ('c0000000-0000-0000-0000-000000000004', 'inventory:adjust'),
    ('c0000000-0000-0000-0000-000000000005', 'branch:read'),
    ('c0000000-0000-0000-0000-000000000006', 'branch:manage')
ON CONFLICT (permission_code) DO NOTHING;

-- ------------------------------------------------------------
-- 2. Global administrators hold every permission (cross-join)
-- ------------------------------------------------------------
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ------------------------------------------------------------
-- 3. Store-scoped operational roles (explicit capability mapping)
-- ------------------------------------------------------------
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM (VALUES
    -- Store Manager: manage its branch, run POS, read orders/inventory,
    -- drive production, view audit and manage notifications.
    ('STORE_MANAGER', 'branch:read'),
    ('STORE_MANAGER', 'branch:manage'),
    ('STORE_MANAGER', 'pos:access'),
    ('STORE_MANAGER', 'order:read'),
    ('STORE_MANAGER', 'inventory:read'),
    ('STORE_MANAGER', 'inventory:adjust'),
    ('STORE_MANAGER', 'audit:read'),
    ('STORE_MANAGER', 'production:queue-read'),
    ('STORE_MANAGER', 'production:item-start'),
    ('STORE_MANAGER', 'production:item-ready'),
    ('STORE_MANAGER', 'notification:manage'),

    -- Cashier: operate the POS and read its own branch orders/queue.
    ('CASHIER', 'pos:access'),
    ('CASHIER', 'order:read'),
    ('CASHIER', 'production:queue-read'),

    -- Barista / Kitchen / Bakery: production queue work only.
    ('BARISTA', 'production:queue-read'),
    ('BARISTA', 'production:item-start'),
    ('BARISTA', 'production:item-ready'),

    ('KITCHEN_STAFF', 'production:queue-read'),
    ('KITCHEN_STAFF', 'production:item-start'),
    ('KITCHEN_STAFF', 'production:item-ready'),

    ('BAKERY_STAFF', 'production:queue-read'),
    ('BAKERY_STAFF', 'production:item-start'),
    ('BAKERY_STAFF', 'production:item-ready')
) AS mapping (role_code, permission_code)
JOIN identity.roles r ON r.role_code = mapping.role_code
JOIN identity.permissions p ON p.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
