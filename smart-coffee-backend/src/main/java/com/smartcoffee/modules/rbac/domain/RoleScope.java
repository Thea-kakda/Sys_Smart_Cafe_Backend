package com.smartcoffee.modules.rbac.domain;

/**
 * Assignment scope a role is pinned to since V052 (ck_roles_role_scope):
 * GLOBAL roles are granted with store_id NULL only (SUPER_ADMIN, ADMIN,
 * CUSTOMER); STORE roles are always granted against one active branch.
 */
public enum RoleScope {
    GLOBAL,
    STORE
}
