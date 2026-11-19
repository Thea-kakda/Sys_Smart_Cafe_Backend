package com.smartcoffee.modules.identity.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.identity.api.RoleAssignmentSummary;
import com.smartcoffee.modules.identity.api.RoleResponse;
import com.smartcoffee.modules.identity.persistence.StaffQueryRepository;
import com.smartcoffee.modules.identity.persistence.StaffQueryRepository.RoleAssignmentRow;
import com.smartcoffee.modules.rbac.domain.Role;
import com.smartcoffee.modules.rbac.domain.RoleScope;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository;
import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository.CodeProjection;
import com.smartcoffee.modules.rbac.persistence.RoleRepository;
import com.smartcoffee.modules.rbac.persistence.UserRoleAssignmentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom-role lifecycle administration (V052, SUPER_ADMIN-only capabilities).
 * The eight migration-seeded roles carry is_system=TRUE and are immutable here —
 * the immutability rule (audit finding F5) replaces the old SUPER_ADMIN-name
 * check as the reason a role cannot be renamed or deleted. Deleting a role that
 * ANY assignment still references is refused (409), and a successful delete
 * removes its grant rows first in the same transaction.
 */
@SuppressWarnings("null")
@Service
public class RoleAdministrationService {

    private final RoleRepository roles;
    private final RolePermissionManagementRepository grants;
    private final UserRoleAssignmentRepository assignments;
    private final StaffQueryRepository staff;
    private final AuditWriter audit;

    public RoleAdministrationService(RoleRepository roles, RolePermissionManagementRepository grants,
            UserRoleAssignmentRepository assignments, StaffQueryRepository staff, AuditWriter audit) {
        this.roles = roles;
        this.grants = grants;
        this.assignments = assignments;
        this.staff = staff;
        this.audit = audit;
    }

    @Transactional
    public RoleResponse create(CurrentActor actor, String roleCode, String roleName, String description,
            String roleScope) {
        if (roles.existsByCode(roleCode)) {
            throw new ConflictException("ROLE_CODE_EXISTS", "A role with that code already exists");
        }
        Role role = Role.createCustom(roleCode, roleName, description, RoleScope.valueOf(roleScope));
        Role saved = roles.save(role);
        audit.success(actor.userId(), null, "ROLE_CREATED", "SECURITY", "ROLE", saved.id(), null,
                detail(saved.id()), "scope=" + roleScope);
        return detail(saved.id());
    }

    @Transactional
    public RoleResponse update(CurrentActor actor, UUID roleId, String roleName, String description) {
        Role role = requireRole(roleId);
        if (role.isSystem()) {
            throw new ForbiddenException("PROTECTED_ROLE", "System roles cannot be modified");
        }
        if (roleName == null && description == null) {
            throw new ValidationException("ROLE_UPDATE_EMPTY", "At least one of roleName/description is required");
        }
        String newName = roleName != null ? roleName : role.name();
        String newDescription = description != null ? description : role.description();
        role.rename(newName, newDescription);
        roles.saveAndFlush(role);
        audit.success(actor.userId(), null, "ROLE_UPDATED", "SECURITY", "ROLE", roleId, null,
                detail(roleId), null);
        return detail(roleId);
    }

    @Transactional
    public void delete(CurrentActor actor, UUID roleId) {
        Role role = requireRole(roleId);
        if (role.isSystem()) {
            throw new ForbiddenException("PROTECTED_ROLE", "System roles cannot be deleted");
        }
        if (assignments.existsByRoleId(roleId)) {
            throw new ConflictException("ROLE_IN_USE",
                    "The role still has assignments (active or historical) and cannot be deleted");
        }
        grants.deleteGrants(roleId);
        roles.delete(role);
        roles.flush();
        audit.success(actor.userId(), null, "ROLE_DELETED", "SECURITY", "ROLE", roleId,
                roleResponseSnapshot(role), null, "code=" + role.code());
    }

    @Transactional(readOnly = true)
    public Page<RoleAssignmentSummary> assignments(UUID roleId, int page, int size) {
        requireRole(roleId);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<RoleAssignmentRow> rows = staff.assignmentsByRole(roleId, PageRequest.of(Math.max(page, 0), safeSize));
        return rows.map(r -> new RoleAssignmentSummary(r.getAssignmentId(), r.getUserId(), r.getUserStatus(),
                r.getStoreId(), r.getAssignedAt(), r.getExpiresAt(), r.getRevokedAt()));
    }

    // ---------------------------------------------------------------- helpers

    private Role requireRole(UUID roleId) {
        return roles.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "Role not found"));
    }

    /** Post-write view of a role: entity fields plus its current grant codes. */
    private RoleResponse detail(UUID roleId) {
        Role role = requireRole(roleId);
        return roleResponseSnapshot(role);
    }

    private RoleResponse roleResponseSnapshot(Role role) {
        List<String> codes = grants.grantedCodes(role.id()).stream()
                .map(CodeProjection::getPermissionCode).distinct().sorted().toList();
        return new RoleResponse(role.id(), role.code(), role.name(), codes, role.isSystem(),
                role.roleScope().name(), role.description());
    }
}
