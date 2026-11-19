-- =============================================================================
-- V028: User Directory read permission ("see users in the UI")
-- =============================================================================
-- Guards the protected management user directory:
--   GET /api/v1/management/users            user:admin-read
--   GET /api/v1/management/users/{userId}   user:admin-read
--
-- The directory spans ALL identity users (staff AND customers) and therefore
-- exposes personal data (email, phone). It is deliberately NOT the operational
-- staff:read (V023): staff:read stays branch-scoped administration of staff
-- accounts only, while user:admin-read is a global-administrative privilege.
-- Grants therefore go to SUPER_ADMIN and ADMIN only — STORE_MANAGER continues
-- to use /api/v1/management/staff for their own rosters, which keeps customer
-- personal data out of branch-level access.
--
-- Deterministic UUID uses a fresh bf000... namespace (a000 V023, b000 V010,
-- ba000 V024, bc000 V025, bd000 V026, be000 V027, c/d/e/f V013/V017/V018/V020).
-- Idempotent via ON CONFLICT.
-- =============================================================================

INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('bf000000-0000-0000-0000-000000000001', 'user:admin-read')
ON CONFLICT (permission_code) DO NOTHING;

-- SUPER_ADMIN and ADMIN hold every permission (cross-join covers the new code).
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;
