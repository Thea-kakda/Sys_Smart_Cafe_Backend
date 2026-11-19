-- =============================================================================
-- V034: Inventory purchasing permissions
-- =============================================================================
-- Guards the procurement write path added in V033.
--
--   supplier:manage    POST/PATCH /api/v1/suppliers (supplier master CRUD)
--   purchase:manage    POST /api/v1/stores/{id}/purchase-orders + submit/cancel
--                      and the purchase-order reads
--   inventory:receive  POST /api/v1/stores/{id}/purchase-orders/{id}/receipts
--                      (goods receipt — the valued stock-in on the ledger)
--
-- Deterministic UUIDs use a fresh e400... namespace (c200=V031, c300=V032,
-- d000=V017). Idempotent via ON CONFLICT.
--
-- Grants:
--   SUPER_ADMIN, ADMIN  → every permission (cross-join covers the three new codes).
--   STORE_MANAGER       → all three (branch purchasing + receiving).
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('e4000000-0000-0000-0000-000000000001', 'supplier:manage'),
    ('e4000000-0000-0000-0000-000000000002', 'purchase:manage'),
    ('e4000000-0000-0000-0000-000000000003', 'inventory:receive')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- STORE_MANAGER gains the three purchasing codes.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code IN ('supplier:manage', 'purchase:manage', 'inventory:receive')
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
