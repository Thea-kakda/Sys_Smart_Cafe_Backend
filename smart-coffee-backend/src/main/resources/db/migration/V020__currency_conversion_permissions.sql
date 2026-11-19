-- =============================================================================
-- V020: Currency conversion + rate management permissions (Checkpoint 5B / 10)
-- =============================================================================
-- currency-conversion:preview -> read-only conversion preview (POST
--                                /api/v1/currency-conversions/preview).
-- currency-rate:read          -> list exchange rates for a scope.
-- currency-rate:manage        -> create / supersede rates (Checkpoint 10).
--
-- Deterministic seed UUIDs continue the e000... V018 convention with f000...
-- Idempotent by design (ON CONFLICT DO NOTHING).
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('f0000000-0000-0000-0000-000000000001', 'currency-conversion:preview'),
    ('f0000000-0000-0000-0000-000000000002', 'currency-rate:read'),
    ('f0000000-0000-0000-0000-000000000003', 'currency-rate:manage')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Cashiers preview conversions at the counter; managers additionally read and
-- maintain branch rates.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM (VALUES
    ('CASHIER', 'currency-conversion:preview'),
    ('CASHIER', 'currency-rate:read'),
    ('STORE_MANAGER', 'currency-conversion:preview'),
    ('STORE_MANAGER', 'currency-rate:read'),
    ('STORE_MANAGER', 'currency-rate:manage')
) AS mapping (role_code, permission_code)
JOIN identity.roles r ON r.role_code = mapping.role_code
JOIN identity.permissions p ON p.permission_code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
