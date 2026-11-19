SET default_transaction_read_only = on;
BEGIN READ ONLY;
\echo '=== G0 flyway history (tail + anomalies) ==='
SELECT installed_rank, version, description, checksum, success, installed_on
FROM public.flyway_schema_history WHERE installed_rank > 45 OR success = false ORDER BY installed_rank;
SELECT count(*) AS total_rows, max(version::int) FILTER (WHERE version IS NOT NULL) AS max_version, bool_and(success) AS all_success FROM public.flyway_schema_history;
\echo '=== G1 counts ==='
SELECT (SELECT count(*) FROM identity.roles) roles,
       (SELECT count(*) FROM identity.permissions) permissions,
       (SELECT count(*) FROM identity.role_permissions) role_permissions,
       (SELECT count(*) FROM organization.stores) stores,
       (SELECT count(*) FROM organization.stores WHERE active) active_stores;
\echo '=== V052 columns present? ==='
SELECT table_name, column_name FROM information_schema.columns
WHERE table_schema='identity' AND ((table_name='roles' AND column_name IN ('is_system','description','role_scope'))
   OR (table_name='user_role_assignments' AND column_name IN ('assigned_by_user_id','revoked_by_user_id','revocation_reason')));
\echo '=== G2 SUPER_ADMIN missing perms ==='
SELECT p.permission_code FROM identity.permissions p
WHERE NOT EXISTS (SELECT 1 FROM identity.role_permissions rp JOIN identity.roles r ON r.role_id = rp.role_id
                  WHERE r.role_code = 'SUPER_ADMIN' AND rp.permission_id = p.permission_id);
\echo '=== G3 SUPER_ADMIN holders ==='
SELECT a.assignment_id, a.user_id, u.status, a.store_id, a.assigned_at, a.expires_at, a.revoked_at
FROM identity.user_role_assignments a
JOIN identity.roles r ON r.role_id = a.role_id JOIN identity.users u ON u.user_id = a.user_id
WHERE r.role_code = 'SUPER_ADMIN';
\echo '=== G4 active assignments by role/scope ==='
SELECT r.role_code, (a.store_id IS NULL) AS is_global, count(*) AS n
FROM identity.user_role_assignments a JOIN identity.roles r ON r.role_id = a.role_id
WHERE a.revoked_at IS NULL AND (a.expires_at IS NULL OR a.expires_at > now())
GROUP BY 1,2 ORDER BY 1,2;
\echo '=== G4b active GLOBAL ADMIN assignments ==='
SELECT a.assignment_id, a.user_id, u.status, a.assigned_at, a.expires_at
FROM identity.user_role_assignments a
JOIN identity.roles r ON r.role_id = a.role_id JOIN identity.users u ON u.user_id = a.user_id
WHERE r.role_code = 'ADMIN' AND a.store_id IS NULL AND a.revoked_at IS NULL
  AND (a.expires_at IS NULL OR a.expires_at > now());
\echo '=== G4c scope violations ==='
SELECT r.role_code, (a.store_id IS NULL) AS is_global, a.assignment_id, a.user_id
FROM identity.user_role_assignments a JOIN identity.roles r ON r.role_id = a.role_id
WHERE a.revoked_at IS NULL
  AND ((r.role_code NOT IN ('SUPER_ADMIN','CUSTOMER') AND a.store_id IS NULL)
    OR (r.role_code = 'SUPER_ADMIN' AND a.store_id IS NOT NULL));
\echo '=== G4d store-scoped CUSTOMER assignments ==='
SELECT a.assignment_id, a.user_id, a.store_id, a.assigned_at
FROM identity.user_role_assignments a JOIN identity.roles r ON r.role_id = a.role_id
WHERE r.role_code='CUSTOMER' AND a.store_id IS NOT NULL AND a.revoked_at IS NULL;
\echo '=== G5 parity ==='
SELECT r.role_code, count(*) FROM identity.role_permissions rp JOIN identity.roles r ON r.role_id = rp.role_id
GROUP BY 1 ORDER BY 1;
\echo '=== G6 audit conventions ==='
SELECT category, action, count(*) FROM audit.audit_logs GROUP BY 1,2 ORDER BY 3 DESC LIMIT 50;
ROLLBACK;
