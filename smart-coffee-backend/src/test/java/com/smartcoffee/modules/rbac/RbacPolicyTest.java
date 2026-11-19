package com.smartcoffee.modules.rbac;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.BusinessException;
import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.rbac.application.RbacPolicy;
import com.smartcoffee.modules.rbac.domain.Role;
import com.smartcoffee.modules.rbac.domain.RoleScope;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository.CodeProjection;
import com.smartcoffee.modules.rbac.persistence.RoleRepository;
import com.smartcoffee.modules.rbac.persistence.UserRoleAssignmentRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit verification of the V052 privilege ladder (single source of truth:
 * {@link RbacPolicy}). Covers the assign rules (baseline, GLOBAL ⇒
 * role:privileged-assign, no-escalation, no-self, store-in-scope), the revoke
 * twin (self-revoke of a GLOBAL role denied, store-scope allowed), the
 * permission-replacement rules (SUPER_ADMIN immutable, escalation, IAM
 * prefixes) and the last-SUPER_ADMIN guarded-change contract.
 */
class RbacPolicyTest {

    private RolePermissionManagementRepository grants;
    private RoleRepository roles;
    private UserRoleAssignmentRepository assignments;
    private AuditWriter audit;
    private RbacPolicy policy;

    @BeforeEach
    void setUp() {
        grants = mock(RolePermissionManagementRepository.class);
        roles = mock(RoleRepository.class);
        assignments = mock(UserRoleAssignmentRepository.class);
        audit = mock(AuditWriter.class);
        policy = new RbacPolicy(grants, roles, assignments, audit);
    }

    private CurrentActor actor(String... permissionCodes) {
        return new CurrentActor(UUID.randomUUID(), "STAFF", Set.of(permissionCodes), Set.of(), true);
    }

    private Role role(String code, RoleScope scope, String... grantedPermissionCodes) {
        Role role = Role.createCustom(code, code, null, scope);
        List<CodeProjection> projectionList = projections(grantedPermissionCodes);
        when(grants.grantedCodes(role.id())).thenReturn(projectionList);
        return role;
    }

    private List<CodeProjection> projections(String... codes) {
        return java.util.Arrays.stream(codes).map(c -> {
            CodeProjection p = mock(CodeProjection.class);
            when(p.getPermissionCode()).thenReturn(c);
            return p;
        }).toList();
    }

    // ---------------------------------------------------------------- assign

    @Test
    void assignStoreScopedRoleWithHeldPermissionsIsAllowed() {
        CurrentActor admin = actor(PermissionCodes.STAFF_ROLE_ASSIGN, "order:read", "order:create");
        Role barista = role("BARISTA", RoleScope.STORE, "order:read", "order:create");

        assertDoesNotThrow(() -> policy.checkAssign(admin, barista, UUID.randomUUID(), UUID.randomUUID()));
        verify(audit, never()).denied(any(), any(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void assignWithoutBaselineIsDenied() {
        CurrentActor nobody = actor("order:read");
        Role barista = role("BARISTA", RoleScope.STORE, "order:read");

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkAssign(nobody, barista, UUID.randomUUID(), UUID.randomUUID()));
        assertEquals("AUTH_FORBIDDEN", ex.code());
    }

    @Test
    void assignGlobalRoleWithoutPrivilegedLeverIsDenied() {
        // ADMIN after V052: holds staff:role-assign and every permission the
        // ADMIN role carries, but NOT role:privileged-assign — so even a fully
        // non-escalating assignment of a GLOBAL role must be refused.
        CurrentActor admin = actor(PermissionCodes.STAFF_ROLE_ASSIGN, "order:read");
        Role adminRole = role("ADMIN", RoleScope.GLOBAL, "order:read");

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkAssign(admin, adminRole, UUID.randomUUID(), null));
        assertEquals("PRIVILEGED_ASSIGN_REQUIRED", ex.code());
        // requirePrivileged denies with role=null: the actor never gets far
        // enough for the role row to be named in the audit entry.
        verify(audit).denied(admin.userId(), null, "ROLE_ASSIGN_DENIED", "SECURITY", "ROLE", null,
                "Assigning GLOBAL-scope role 'ADMIN' requires role:privileged-assign");
    }

    @Test
    void assignGlobalRoleWithPrivilegedLeverIsAllowed() {
        CurrentActor root = actor(PermissionCodes.STAFF_ROLE_ASSIGN, PermissionCodes.ROLE_PRIVILEGED_ASSIGN,
                "order:read");
        Role adminRole = role("ADMIN", RoleScope.GLOBAL, "order:read");

        assertDoesNotThrow(() -> policy.checkAssign(root, adminRole, UUID.randomUUID(), null));
    }

    @Test
    void assignRoleCarryingUnheldPermissionIsEscalation() {
        CurrentActor manager = actor(PermissionCodes.STAFF_ROLE_ASSIGN, "order:read");
        Role cashier = role("CASHIER", RoleScope.STORE, "order:read", "payment:refund");

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkAssign(manager, cashier, UUID.randomUUID(), UUID.randomUUID()));
        assertEquals("PERMISSION_ESCALATION", ex.code());
    }

    @Test
    void selfAssignmentIsDenied() {
        CurrentActor root = actor(PermissionCodes.STAFF_ROLE_ASSIGN, PermissionCodes.ROLE_PRIVILEGED_ASSIGN);
        Role adminRole = role("ADMIN", RoleScope.GLOBAL);
        UUID self = root.userId();

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkAssign(root, adminRole, self, null));
        assertEquals("SELF_ROLE_CHANGE", ex.code());
    }

    @Test
    void assignStoreScopedRoleForOutOfScopeStoreIsDenied() {
        CurrentActor branch = new CurrentActor(UUID.randomUUID(), "STAFF",
                Set.of(PermissionCodes.STAFF_ROLE_ASSIGN, "order:read"), Set.of(UUID.randomUUID()), false);
        Role cashier = role("CASHIER", RoleScope.STORE, "order:read");
        UUID foreignStore = UUID.randomUUID();

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkAssign(branch, cashier, UUID.randomUUID(), foreignStore));
        assertEquals("STORE_ACCESS_DENIED", ex.code());
    }

    // ---------------------------------------------------------------- revoke

    @Test
    void revokeGlobalRoleFromSelfIsDenied() {
        CurrentActor root = actor(PermissionCodes.STAFF_ROLE_ASSIGN, PermissionCodes.ROLE_PRIVILEGED_ASSIGN);
        Role adminRole = role("ADMIN", RoleScope.GLOBAL);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkRevoke(root, adminRole, root.userId(), null));
        assertEquals("SELF_ROLE_CHANGE", ex.code());
    }

    @Test
    void revokeStoreScopedRoleFromSelfIsAllowed() {
        CurrentActor branch = new CurrentActor(UUID.randomUUID(), "STAFF",
                Set.of(PermissionCodes.STAFF_ROLE_ASSIGN), Set.of(), true);
        Role cashier = role("CASHIER", RoleScope.STORE);

        assertDoesNotThrow(() -> policy.checkRevoke(branch, cashier, branch.userId(), UUID.randomUUID()));
    }

    @Test
    void revokeGlobalRoleWithoutPrivilegedLeverIsDenied() {
        CurrentActor admin = actor(PermissionCodes.STAFF_ROLE_ASSIGN);
        Role adminRole = role("ADMIN", RoleScope.GLOBAL);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkRevoke(admin, adminRole, UUID.randomUUID(), null));
        assertEquals("PRIVILEGED_ASSIGN_REQUIRED", ex.code());
    }

    // ---------------------------------------------------- replace permissions

    @Test
    void superAdminPermissionSetIsImmutable() {
        CurrentActor root = actor(PermissionCodes.ROLE_PERMISSION_MANAGE, PermissionCodes.ROLE_PRIVILEGED_ASSIGN);
        Role superAdmin = role(RbacPolicy.SUPER_ADMIN, RoleScope.GLOBAL, "order:read");

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkReplacePermissions(root, superAdmin, List.of("order:read")));
        assertEquals("PROTECTED_ROLE", ex.code());
    }

    @Test
    void editingGlobalRolePermissionsRequiresPrivilegedLever() {
        CurrentActor admin = actor(PermissionCodes.ROLE_PERMISSION_MANAGE, "order:read");
        Role adminRole = role("ADMIN", RoleScope.GLOBAL, "order:read");

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkReplacePermissions(admin, adminRole, List.of("order:read")));
        assertEquals("PRIVILEGED_ASSIGN_REQUIRED", ex.code());
    }

    @Test
    void grantingUnheldPermissionIsEscalation() {
        CurrentActor admin = actor(PermissionCodes.ROLE_PERMISSION_MANAGE, PermissionCodes.ROLE_PRIVILEGED_ASSIGN,
                "order:read");
        Role scratch = role("SCRATCH", RoleScope.STORE, "order:read");

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkReplacePermissions(admin, scratch, List.of("order:read", "order:export")));
        assertEquals("PERMISSION_ESCALATION", ex.code());
    }

    @Test
    void grantingHeldIamPermissionStillRequiresPrivilegedLever() {
        // Rule 4: IAM capabilities always need the privileged lever, even when
        // the actor already holds the code.
        CurrentActor admin = actor(PermissionCodes.ROLE_PERMISSION_MANAGE, PermissionCodes.STAFF_READ);
        Role scratch = role("SCRATCH", RoleScope.STORE);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> policy.checkReplacePermissions(admin, scratch, List.of(PermissionCodes.STAFF_READ)));
        assertEquals("PRIVILEGED_ASSIGN_REQUIRED", ex.code());
    }

    @Test
    void replacingWithSubsetOfHeldCodesIsAllowed() {
        CurrentActor root = actor(PermissionCodes.ROLE_PERMISSION_MANAGE, PermissionCodes.ROLE_PRIVILEGED_ASSIGN,
                "order:read", "order:create");
        Role scratch = role("SCRATCH", RoleScope.STORE, "order:read");

        assertDoesNotThrow(
                () -> policy.checkReplacePermissions(root, scratch, List.of("order:read", "order:create")));
    }

    @Test
    void iamPrefixDetectionCoversAllIdentityNamespaces() {
        assertTrue(policy.isIam("staff:read"));
        assertTrue(policy.isIam("role:create"));
        assertTrue(policy.isIam("permission:read"));
        assertTrue(policy.isIam("user:profile-update"));
        assertFalse(policy.isIam("order:read"));
        assertFalse(policy.isIam("staffroom:chat"));
    }

    // --------------------------------------------------- last-SUPER_ADMIN guard

    @Test
    void guardedChangePassesWhenAnotherActiveHolderRemains() {
        Role superAdmin = Role.createCustom(RbacPolicy.SUPER_ADMIN, RbacPolicy.SUPER_ADMIN, null, RoleScope.GLOBAL);
        when(roles.lockByCode(RbacPolicy.SUPER_ADMIN)).thenReturn(Optional.of(superAdmin));
        when(assignments.countActiveGlobalHolders(any(), any())).thenReturn(2L);

        boolean[] ran = { false };
        assertDoesNotThrow(() -> policy.runLastSuperAdminGuarded(() -> ran[0] = true));
        assertTrue(ran[0], "the guarded change must actually run");
    }

    @Test
    void guardedChangeAbortsWhenFinalHolderRemoved() {
        Role superAdmin = Role.createCustom(RbacPolicy.SUPER_ADMIN, RbacPolicy.SUPER_ADMIN, null, RoleScope.GLOBAL);
        when(roles.lockByCode(RbacPolicy.SUPER_ADMIN)).thenReturn(Optional.of(superAdmin));
        when(assignments.countActiveGlobalHolders(any(), any())).thenReturn(0L);

        boolean[] ran = { false };
        BusinessException ex = assertThrows(ConflictException.class,
                () -> policy.runLastSuperAdminGuarded(() -> ran[0] = true));
        assertEquals("LAST_SUPER_ADMIN_PROTECTED", ex.code());
        assertTrue(ran[0], "the write must happen BEFORE the recount so the transaction can roll it back");
    }

    @Test
    void guardedChangeFailsFastWhenRoleMissing() {
        when(roles.lockByCode(RbacPolicy.SUPER_ADMIN)).thenReturn(Optional.empty());

        boolean[] ran = { false };
        BusinessException ex = assertThrows(ConflictException.class,
                () -> policy.runLastSuperAdminGuarded(() -> ran[0] = true));
        assertEquals("ROLE_NOT_FOUND", ex.code());
        assertFalse(ran[0], "nothing may run without the lock");
    }
}
