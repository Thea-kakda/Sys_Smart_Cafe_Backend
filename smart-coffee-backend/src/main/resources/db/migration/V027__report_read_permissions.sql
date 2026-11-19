-- =============================================================================
-- V027: BI report read permissions (Master Prompt §10.3) + report access index
-- =============================================================================
-- Guards the protected report endpoints:
--   GET /api/v1/management/reports/sales-summary      report:sales-read
--   GET /api/v1/management/reports/inventory-cogs     report:inventory-cogs-read
--
-- Deterministic UUIDs use a fresh be000... namespace (verified distinct from
-- a000/b000/c000/d000/e000/f000/ba000/bc000/bd000 seeds). Idempotent ON CONFLICT.
--
-- Grants: global admins via the standing cross-join. STORE_MANAGER gains
-- report:sales-read (branch performance is a store concern — scoping still
-- applies server-side); report:inventory-cogs-read stays admin-only because
-- consumption/ingredient economics are cross-branch.
--
-- Index: sales summary filters ordering.orders by store set + ordered_at range
-- (+ payment_status filter inside the aggregates). ix_order_store_status leads
-- on status, which report queries never equality-filter, so the demonstrated
-- query shape (store range scan over time) gets its own index — mirroring the
-- existing ix_stock_store_time precedent. §10.4 permits migrations only for
-- demonstrated query needs; these queries ship in this phase.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('be000000-0000-0000-0000-000000000001', 'report:sales-read'),
    ('be000000-0000-0000-0000-000000000002', 'report:inventory-cogs-read')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
JOIN identity.permissions p ON p.permission_code = 'report:sales-read'
WHERE r.role_code = 'STORE_MANAGER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS ix_order_store_time
    ON ordering.orders (store_id, ordered_at DESC);
