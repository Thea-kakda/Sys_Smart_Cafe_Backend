package com.smartcoffee.modules.identity.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.identity.api.PermissionResponse;
import com.smartcoffee.modules.identity.api.RoleResponse;
import com.smartcoffee.modules.identity.persistence.UserRepository;
import com.smartcoffee.modules.rbac.application.RbacPolicy;
import com.smartcoffee.modules.rbac.domain.Role;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository.CodeProjection;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository.PermissionProjection;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository.RoleGrantProjection;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository.RoleProjection;
import com.smartcoffee.modules.rbac.persistence.RoleRepository;
import com.smartcoffee.modules.rbac.persistence.UserRoleAssignmentRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protected Roles &amp; Permissions administration (Master Prompt §7). Reads span
 * roles → role_permissions → permissions, so they go through the native
 * {@link RolePermissionManagementRepository} read model; the only write is a
 * transactional replacement of one role's grant set. Grant changes invalidate the
 * security_version of every active member of that role (via identity's own
 * {@link UserRepository}) so outstanding tokens reflect the new authority, and the
 * old and new sets are audited.
 *
 * <p>Safety (Master §7.2): SUPER_ADMIN is immutable, which guarantees role:read /
 * role:permission-manage / permission:read can never be stripped from every
 * administrator — there is always at least one functional global administrator.
 * Custom role creation is intentionally not implemented: roles are system-defined
 * (seeded by migration), documented as a BACKEND API GAP rather than a half-built
 * creation path.
 */
@SuppressWarnings("null")
@Service
public class RolePermissionManagementService {

    private final RolePermissionManagementRepository grants;
    private final UserRoleAssignmentRepository assignments;
    private final UserRepository users;
    private final RoleRepository roleEntities;
    private final RbacPolicy rbacPolicy;
    private final AuditWriter audit;

    public RolePermissionManagementService(RolePermissionManagementRepository grants,
            UserRoleAssignmentRepository assignments, UserRepository users, RoleRepository roleEntities,
            RbacPolicy rbacPolicy, AuditWriter audit) {
        this.grants = grants;
        this.assignments = assignments;
        this.users = users;
        this.roleEntities = roleEntities;
        this.rbacPolicy = rbacPolicy;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        Map<UUID, List<String>> byRole = grants.allGrants().stream()
                .collect(Collectors.groupingBy(RoleGrantProjection::getRoleId,
                        Collectors.mapping(RoleGrantProjection::getPermissionCode, Collectors.toList())));
        List<RoleResponse> out = new ArrayList<>();
        for (RoleProjection r : grants.roles()) {
            out.add(toRole(r, byRole.getOrDefault(r.getRoleId(), List.of())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public RoleResponse roleDetail(UUID roleId) {
        RoleProjection r = grants.findRole(roleId);
        if (r == null) {
            throw new ResourceNotFoundException("ROLE_NOT_FOUND", "Role not found");
        }
        List<String> codes = grants.grantedCodes(roleId).stream().map(CodeProjection::getPermissionCode).toList();
        return toRole(r, codes);
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions() {
        return grants.permissions().stream()
                .map(p -> new PermissionResponse(p.getPermissionId(), p.getPermissionCode(), category(p.getPermissionCode())))
                .toList();
    }

    // ----------------------------------------------------------------- writes

    @Transactional
    public RoleResponse updateRolePermissions(CurrentActor actor, UUID roleId, List<String> requested) {
        RoleProjection role = grants.findRole(roleId);
        if (role == null) {
            throw new ResourceNotFoundException("ROLE_NOT_FOUND", "Role not found");
        }
        Role roleEntity = roleEntities.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "Role not found"));

        // Normalize: trim, drop empties, dedupe while preserving order.
        Set<String> codes = requested == null ? Set.of() : requested.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Resolve against existing permissions; reject any unknown code so no new
        // permission is ever minted through this endpoint (Master §7.2).
        Map<String, UUID> idByCode = codes.isEmpty() ? Map.of()
                : grants.findByCodes(codes).stream()
                        .collect(Collectors.toMap(PermissionProjection::getPermissionCode,
                                PermissionProjection::getPermissionId, (a, b) -> a));
        for (String code : codes) {
            if (!idByCode.containsKey(code)) {
                throw new ValidationException("PERMISSION_UNKNOWN", "Unknown permission code: " + code);
            }
        }

        List<String> before = grants.grantedCodes(roleId).stream().map(CodeProjection::getPermissionCode).toList();

        // V052 privilege ladder FIRST — even a pure no-op is refused for an
        // actor that may not touch this role (GLOBAL scope, unheld grants),
        // so the ladder cannot be probed through harmless-looking calls.
        rbacPolicy.checkReplacePermissions(actor, roleEntity, codes);

        // V052: a pure no-op must not delete/insert or invalidate members.
        Set<String> current = Set.copyOf(before);
        if (current.equals(codes)) {
            return roleDetail(roleId);
        }

        // Transactional replacement of the grant set.
        grants.deleteGrants(roleId);
        for (String code : codes) {
            grants.insertGrant(roleId, idByCode.get(code));
        }

        // Invalidate members so re-authorization reflects the new authority.
        List<UUID> affected = assignments.activeUserIdsByRole(roleId);
        for (UUID userId : affected) {
            users.findById(userId).ifPresent(u -> {
                u.incrementSecurityVersion();
                users.save(u);
            });
        }

        List<String> after = grants.grantedCodes(roleId).stream().map(CodeProjection::getPermissionCode).toList();
        audit.success(actor.userId(), null, "ROLE_PERMISSIONS_UPDATED", "SECURITY", "ROLE", roleId,
                Map.of("permissions", before), Map.of("permissions", after),
                "role=" + role.getRoleCode() + " affectedUsers=" + affected.size());
        return roleDetail(roleId);
    }

    // ---------------------------------------------------------------- helpers

    private RoleResponse toRole(RoleProjection r, List<String> codes) {
        List<String> sorted = codes.stream().distinct().sorted().toList();
        return new RoleResponse(r.getRoleId(), r.getRoleCode(), r.getRoleName(), sorted,
                r.getIsSystem(), r.getRoleScope(), r.getDescription());
    }

    /** Category is the code prefix before the first colon; null when there is none. */
    private String category(String code) {
        int idx = code.indexOf(':');
        return idx > 0 ? code.substring(0, idx) : null;
    }
}
