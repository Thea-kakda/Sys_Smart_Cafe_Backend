-- =============================================================================
-- V040: Variant + option-inventory-effect management permissions.
-- =============================================================================
-- Slice 1 of the enterprise catalog upgrade introduces two new write paths on
-- top of the existing product:manage / ingredient:manage surface:
--
--   variant:manage        create/update/activate/deactivate/set-default/reorder
--                         product variants and delete (soft) them.
--   option-effect:manage  upsert / clear the inventory effect attached to a
--                         customization option (ADD / REMOVE / REPLACE /
--                         MULTIPLY).
--
-- Granted to the same audience as product:manage (SUPER_ADMIN, ADMIN) and
-- ingredient:manage (STORE_MANAGER): the capability is a superset of product
-- master data and does not need to be shippable to a narrower role in this
-- slice. Idempotent via ON CONFLICT.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('f3000000-0000-0000-0000-000000000001', 'variant:manage'),
    ('f3000000-0000-0000-0000-000000000002', 'option-effect:manage')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER gains the two new management codes.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code IN
    ('variant:manage', 'option-effect:manage')
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
