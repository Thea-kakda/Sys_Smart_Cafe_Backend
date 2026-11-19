-- =============================================================================
-- V052: SUPER_ADMIN RBAC hardening (forward-only)
-- -----------------------------------------------------------------------------
--  1. identity.roles: is_system / description / role_scope
--  2. identity.user_role_assignments: provenance columns
--  3. Five SUPER_ADMIN-only permissions: role:create, role:update, role:delete,
--     role:assignment-read, role:privileged-assign
--  4. Idempotent invariant backfill: SUPER_ADMIN holds every permission
--
-- DELIBERATE DEVIATION from the V013..V048 convention: the five new codes are
-- NOT granted to ADMIN. Existing ADMIN grants are untouched. The standing
-- cross-join pattern "SUPER_ADMIN AND ADMIN" is deliberately narrowed here to
-- SUPER_ADMIN only: with ADMIN's grant set identical to SUPER_ADMIN's today
-- (Gate G5: 63 = 63), the privilege to mint roles, edit GLOBAL-role permission
-- sets and grant IAM capabilities must stay out of ADMIN's reach until the
-- owner explicitly delegates it via role:privileged-assign.
--
-- Scope decision evidence (Gate G4, live audit 2026-11): CUSTOMER assignments
-- are overwhelmingly global (13 with store_id NULL) with a single store-scoped
-- outlier, and CustomerRegistrationService resolves the CUSTOMER role for
-- self-registration accounts that never bind a branch. role_scope for CUSTOMER
-- is therefore set to 'GLOBAL'. The one store-scoped CUSTOMER assignment is a
-- reported data anomaly and is intentionally NOT rewritten here.
--
-- Does not modify any applied migration. No destructive statements. No
-- triggers, no functions, no user/credential/assignment seeding.
-- =============================================================================

-- 1. Role metadata ------------------------------------------------------------
ALTER TABLE identity.roles
    ADD COLUMN IF NOT EXISTS is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS description VARCHAR(255),
    ADD COLUMN IF NOT EXISTS role_scope  VARCHAR(10)  NOT NULL DEFAULT 'STORE';

ALTER TABLE identity.roles
    ADD CONSTRAINT ck_roles_role_scope CHECK (role_scope IN ('GLOBAL', 'STORE'));

-- The eight seeded roles are system-defined and must never be renamable or
-- deletable through the new role administration endpoints.
UPDATE identity.roles
SET is_system = TRUE
WHERE role_code IN ('CUSTOMER', 'SUPER_ADMIN', 'ADMIN', 'STORE_MANAGER',
                    'CASHIER', 'BARISTA', 'KITCHEN_STAFF', 'BAKERY_STAFF');

-- Global scope mirrors PermissionQueryRepository.global, which today hard-codes
-- the SUPER_ADMIN/ADMIN + store_id IS NULL pair (verified in code, Gate G11).
UPDATE identity.roles
SET role_scope = 'GLOBAL'
WHERE role_code IN ('SUPER_ADMIN', 'ADMIN');

-- CUSTOMER: evidence recorded in the header (13 of 14 active assignments are
-- global; self-registered customers are never branch-bound).
UPDATE identity.roles
SET role_scope = 'GLOBAL'
WHERE role_code = 'CUSTOMER';

-- 2. Assignment provenance ----------------------------------------------------
ALTER TABLE identity.user_role_assignments
    ADD COLUMN IF NOT EXISTS assigned_by_user_id UUID,
    ADD COLUMN IF NOT EXISTS revoked_by_user_id  UUID,
    ADD COLUMN IF NOT EXISTS revocation_reason   VARCHAR(255);

ALTER TABLE identity.user_role_assignments
    ADD CONSTRAINT fk_assignment_assigned_by
        FOREIGN KEY (assigned_by_user_id) REFERENCES identity.users (user_id),
    ADD CONSTRAINT fk_assignment_revoked_by
        FOREIGN KEY (revoked_by_user_id)  REFERENCES identity.users (user_id);

-- 3. New permissions ----------------------------------------------------------
-- ba000000-0001..0003 are V024's role:read / role:permission-manage /
-- permission:read; the namespace continues at ...0004 (Gate: no collision).
INSERT INTO identity.permissions (permission_id, permission_code) VALUES
    ('ba000000-0000-0000-0000-000000000004', 'role:create'),
    ('ba000000-0000-0000-0000-000000000005', 'role:update'),
    ('ba000000-0000-0000-0000-000000000006', 'role:delete'),
    ('ba000000-0000-0000-0000-000000000007', 'role:assignment-read'),
    ('ba000000-0000-0000-0000-000000000008', 'role:privileged-assign')
ON CONFLICT (permission_code) DO NOTHING;

-- 4. SUPER_ADMIN holds every permission (idempotent; also repairs any gap) ----
-- Gate G2 found zero missing grants today, so this is a pure invariant safety
-- net: any future migration that seeds a permission without granting it to
-- SUPER_ADMIN is repaired here and caught by the CI invariant test.
INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.role_code = 'SUPER_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
