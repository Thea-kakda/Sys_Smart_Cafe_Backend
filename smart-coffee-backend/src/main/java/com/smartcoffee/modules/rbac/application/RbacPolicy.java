package com.smartcoffee.modules.rbac.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.rbac.domain.Role;
import com.smartcoffee.modules.rbac.domain.RoleScope;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository.CodeProjection;
import com.smartcoffee.modules.rbac.persistence.RoleRepository;
import com.smartcoffee.modules.rbac.persistence.UserRoleAssignmentRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for privileged RBAC decisions (V052, audit findings
 * F2/F4/F5/F7). BOTH the staff role-assignment service and the role-permission
 * service delegate here so the privilege rules cannot drift between them.
 *
 * <p>Policy summary:
 * <ul>
 *   <li>{@code staff:role-assign} is still the baseline for touching assignments.</li>
 *   <li>GLOBAL-scope roles additionally require {@code role:privileged-assign} —
 *       the only lever ADMIN does NOT hold after V052, which is what finally
 *       separates ADMIN from SUPER_ADMIN.</li>
 *   <li>No privilege escalation: an actor can never grant a permission it does
 *       not hold itself, and IAM capabilities (staff:/role:/permission:/user:)
 *       always need {@code role:privileged-assign}.</li>
 *   <li>SUPER_ADMIN is immutable; custom roles are the only creatable roles.</li>
 *   <li>The last active SUPER_ADMIN cannot be revoked, disabled or expired:
 *       the mutating write happens under a pessimistic lock on the SUPER_ADMIN
 *       role row and the holder count is re-checked AFTER the write, so a
 *       violation aborts the surrounding transaction.</li>
 * </ul>
 *
 * <p>All methods expect to run INSIDE the caller's transaction
 * ({@code AuditWriter} enforces MANDATORY propagation on denials).
 */
@Component
public class RbacPolicy {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    /** Permission-code prefixes that grant identity-administration capability. */
    private static final Set<String> IAM_PREFIXES = Set.of("staff:", "role:", "permission:", "user:");

    private final RolePermissionManagementRepository grants;
    private final RoleRepository roles;
    private final UserRoleAssignmentRepository assignments;
    private final AuditWriter audit;

    public RbacPolicy(RolePermissionManagementRepository grants, RoleRepository roles,
            UserRoleAssignmentRepository assignments, AuditWriter audit) {
        this.grants = grants;
        this.roles = roles;
        this.assignments = assignments;
        this.audit = audit;
    }

    // ------------------------------------------------------------- assignment

    /**
     * Rules 1-7 of the assign flow. Store existence and duplicate-active checks
     * stay with the calling service (they need its repositories); everything
     * privilege-related lives here.
     */
    public void checkAssign(CurrentActor actor, Role role, UUID targetUserId, UUID storeId) {
        requireAssignmentBaseline(actor, "ROLE_ASSIGN_DENIED");
        if (role.roleScope() == RoleScope.GLOBAL) {
            requirePrivileged(actor, "ROLE_ASSIGN_DENIED",
                    "Assigning GLOBAL-scope role '" + role.code() + "' requires role:privileged-assign");
        }
        requireNoEscalation(actor, grantedCodes(role.id()), "ROLE_ASSIGN_DENIED", role, null);
        if (actor.userId().equals(targetUserId)) {
            deny(actor, "ROLE_ASSIGN_DENIED", role, storeId, "Self role changes are not allowed");
            throw new ForbiddenException("SELF_ROLE_CHANGE", "You cannot change your own roles");
        }
        if (role.roleScope() == RoleScope.STORE && storeId != null && !actor.canAccessStore(storeId)) {
            deny(actor, "ROLE_ASSIGN_DENIED", role, storeId, "Store out of caller scope");
            throw new ForbiddenException("STORE_ACCESS_DENIED", "Store access denied");
        }
    }

    /**
     * Revoke-side twin of {@link #checkAssign}: same privilege rules for the
     * assignment's role, plus "no self revoke of a privileged role". Revoking
     * one's own STORE-scoped role is allowed (stepping down from a branch).
     */
    public void checkRevoke(CurrentActor actor, Role role, UUID targetUserId, UUID storeId) {
        requireAssignmentBaseline(actor, "ROLE_REVOKE_DENIED");
        if (role.roleScope() == RoleScope.GLOBAL) {
            requirePrivileged(actor, "ROLE_REVOKE_DENIED",
                    "Revoking GLOBAL-scope role '" + role.code() + "' requires role:privileged-assign");
            if (actor.userId().equals(targetUserId)) {
                deny(actor, "ROLE_REVOKE_DENIED", role, storeId, "Self revoke of a privileged role");
                throw new ForbiddenException("SELF_ROLE_CHANGE", "You cannot revoke your own privileged roles");
            }
        }
    }

    // ------------------------------------------------------- role permissions

    /**
     * Rules for replacing one role's permission set. Returns the resolved
     * "added" codes (diff against current grants) so the caller bumps
     * security_version only when something actually changed.
     */
    public void checkReplacePermissions(CurrentActor actor, Role role, Collection<String> requestedCodes) {
        if (SUPER_ADMIN.equals(role.code())) {
            deny(actor, "ROLE_PERMISSIONS_DENIED", role, null, "SUPER_ADMIN permission set is immutable");
            throw new ForbiddenException("PROTECTED_ROLE",
                    "The SUPER_ADMIN baseline permissions cannot be modified");
        }
        if (role.roleScope() == RoleScope.GLOBAL) {
            requirePrivileged(actor, "ROLE_PERMISSIONS_DENIED",
                    "Editing permissions of GLOBAL-scope role '" + role.code() + "' requires role:privileged-assign");
        }
        java.util.LinkedHashSet<String> added = new java.util.LinkedHashSet<>(grantedCodes(role.id()));
        for (String code : requestedCodes) {
            if (!added.contains(code)) {
                // Rule 3: never grant what the actor does not hold themselves.
                if (!actor.hasPermission(code)) {
                    deny(actor, "ROLE_PERMISSIONS_DENIED", role, null,
                            "Actor does not hold permission '" + code + "'");
                    throw new ForbiddenException("PERMISSION_ESCALATION",
                            "Cannot grant a permission you do not hold: " + code);
                }
                // Rule 4: IAM capabilities always need the privileged lever.
                if (isIam(code)) {
                    requirePrivileged(actor, "ROLE_PERMISSIONS_DENIED",
                            "Granting IAM permission '" + code + "' requires role:privileged-assign");
                }
            }
        }
    }

    public boolean isIam(String permissionCode) {
        return IAM_PREFIXES.stream().anyMatch(permissionCode::startsWith);
    }

    // --------------------------------------------------- last-SUPER_ADMIN guard

    /**
     * Runs {@code change} (which must saveAndFlush) between a pessimistic lock
     * on the SUPER_ADMIN role row and the post-change holder recount, inside
     * the caller's transaction. Reaching zero active holders aborts the whole
     * transaction with 409, so concurrent revokes/disables/expiries can never
     * remove the final SUPER_ADMIN.
     */
    public void runLastSuperAdminGuarded(Runnable change) {
        Role superAdmin = roles.lockByCode(SUPER_ADMIN)
                .orElseThrow(() -> new ConflictException("ROLE_NOT_FOUND", "Role not found"));
        change.run();
        if (assignments.countActiveGlobalHolders(superAdmin.id(), Instant.now()) == 0) {
            throw new ConflictException("LAST_SUPER_ADMIN_PROTECTED",
                    "At least one active SUPER_ADMIN must remain");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void requireAssignmentBaseline(CurrentActor actor, String action) {
        if (!actor.hasPermission(PermissionCodes.STAFF_ROLE_ASSIGN)) {
            deny(actor, action, null, null, "Missing staff:role-assign");
            throw new ForbiddenException("Missing required permission");
        }
    }

    private void requirePrivileged(CurrentActor actor, String action, String clientReason) {
        if (!actor.hasPermission(PermissionCodes.ROLE_PRIVILEGED_ASSIGN)) {
            deny(actor, action, null, null, clientReason);
            throw new ForbiddenException("PRIVILEGED_ASSIGN_REQUIRED",
                    "This action requires role:privileged-assign");
        }
    }

    /** Rule 4 of assign: every permission of the role must be held by the actor. */
    private void requireNoEscalation(CurrentActor actor, Set<String> roleCodes, String action, Role role,
            UUID storeId) {
        for (String code : roleCodes) {
            if (!actor.hasPermission(code)) {
                deny(actor, action, role, storeId, "Role carries unheld permission '" + code + "'");
                throw new ForbiddenException("PERMISSION_ESCALATION",
                        "Cannot assign a role carrying permissions you do not hold: " + code);
            }
        }
    }

    private Set<String> grantedCodes(UUID roleId) {
        return grants.grantedCodes(roleId).stream()
                .map(CodeProjection::getPermissionCode)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void deny(CurrentActor actor, String action, Role role, UUID storeId, String reason) {
        audit.denied(actor.userId(), storeId, action, "SECURITY", "ROLE", role == null ? null : role.id(), reason);
    }
}
