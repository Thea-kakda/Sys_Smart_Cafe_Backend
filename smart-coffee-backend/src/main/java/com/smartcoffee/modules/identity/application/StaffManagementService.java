package com.smartcoffee.modules.identity.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.i18n.LocaleNormalizer;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.auth.persistence.AuthSessionRepository;
import com.smartcoffee.modules.identity.api.CreateStaffRequest;
import com.smartcoffee.modules.identity.api.RoleAssignmentRequest;
import com.smartcoffee.modules.identity.api.StaffAssignmentView;
import com.smartcoffee.modules.identity.api.StaffResponse;
import com.smartcoffee.modules.identity.api.StaffStatusRequest;
import com.smartcoffee.modules.identity.api.UpdateStaffRequest;
import com.smartcoffee.modules.identity.domain.User;
import com.smartcoffee.modules.identity.domain.UserCredential;
import com.smartcoffee.modules.identity.domain.UserStatus;
import com.smartcoffee.modules.identity.persistence.StaffQueryRepository;
import com.smartcoffee.modules.identity.persistence.StaffQueryRepository.StaffAssignmentProjection;
import com.smartcoffee.modules.identity.persistence.StaffQueryRepository.StaffUserProjection;
import com.smartcoffee.modules.identity.persistence.UserCredentialRepository;
import com.smartcoffee.modules.identity.persistence.UserRepository;
import com.smartcoffee.modules.rbac.application.RbacPolicy;
import com.smartcoffee.modules.rbac.domain.Role;
import com.smartcoffee.modules.rbac.domain.RoleScope;
import com.smartcoffee.modules.rbac.domain.UserRoleAssignment;
import com.smartcoffee.modules.rbac.persistence.RoleRepository;
import com.smartcoffee.modules.rbac.persistence.UserRoleAssignmentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protected Staff Administration (Master Prompt §6). Coordinates the identity
 * aggregate (user, credential), the RBAC aggregate (role, assignment) and the
 * audit module. Reads that span aggregates go through {@link StaffQueryRepository};
 * all writes go through the owning JPA repositories, so no module reaches into
 * another's tables. Only the global administrative roles are granted the staff
 * permissions (seeded in V023), so callers are always global — the store-scoped
 * manager case is a documented follow-up gap rather than an insecure partial path.
 */
@SuppressWarnings("null")
@Service
public class StaffManagementService {

    /** Roles that are global-only (store_id must be NULL); everything else is store-scoped. */
    private static final Set<String> GLOBAL_ADMIN_ROLES = Set.of("SUPER_ADMIN", "ADMIN");
    /** A syntactically valid UUID used as a sentinel when the store filter is off (never matched). */
    private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
    private static final Set<String> SORTABLE = Set.of("fullName", "email", "status", "createdAt", "updatedAt");

    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final StaffQueryRepository staff;
    private final RoleRepository roles;
    private final UserRoleAssignmentRepository assignments;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final AuditWriter audit;
    private final RbacPolicy rbacPolicy;

    public StaffManagementService(UserRepository users, UserCredentialRepository credentials,
            StaffQueryRepository staff, RoleRepository roles, UserRoleAssignmentRepository assignments,
            AuthSessionRepository sessions, PasswordEncoder passwords, AuditWriter audit, RbacPolicy rbacPolicy) {
        this.users = users;
        this.credentials = credentials;
        this.staff = staff;
        this.roles = roles;
        this.assignments = assignments;
        this.sessions = sessions;
        this.passwords = passwords;
        this.audit = audit;
        this.rbacPolicy = rbacPolicy;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Page<StaffResponse> list(String search, String status, String roleCode, UUID storeId,
            String sort, int page, int size) {
        Instant now = Instant.now();
        int safeSize = Math.min(Math.max(size, 1), 100);
        String q = (search == null || search.isBlank()) ? "" : search.trim();
        boolean statusFilter = status != null && !status.isBlank();
        boolean roleFilter = roleCode != null && !roleCode.isBlank();
        boolean storeFilter = storeId != null;
        Sort sortSpec = buildSort(sort);

        String storeIdText = (storeId != null) ? storeId.toString() : NIL_UUID;

        Page<StaffUserProjection> result = staff.searchUsers(q, statusFilter, statusFilter ? status : "",
                roleFilter, roleFilter ? roleCode : "", storeFilter, storeIdText,
                now, PageRequest.of(Math.max(page, 0), safeSize, sortSpec));

        List<UUID> ids = result.getContent().stream().map(StaffUserProjection::getUserId).toList();
        Map<UUID, List<StaffAssignmentView>> grouped = ids.isEmpty() ? Map.of()
                : groupAssignments(staff.assignmentsByUserIds(ids), now);

        return result.map(u -> toResponse(u,
                activeOnly(grouped.getOrDefault(u.getUserId(), List.of())), false));
    }

    @Transactional(readOnly = true)
    public StaffResponse detail(UUID userId) {
        Instant now = Instant.now();
        StaffUserProjection u = staff.findUser(userId);
        if (u == null) {
            throw new ResourceNotFoundException("USER_NOT_FOUND", "Staff member not found");
        }
        List<StaffAssignmentView> all = mapAssignments(staff.assignmentsByUserIds(List.of(userId)), now);
        return toResponse(u, all, true);
    }

    // ----------------------------------------------------------------- writes

    @Transactional
    public StaffResponse create(CurrentActor actor, CreateStaffRequest req) {
        String language = LocaleNormalizer.normalize(req.preferredLanguage());
        String email = req.email().trim().toLowerCase();
        if (users.existsByNormalizedEmail(email)) {
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "Email already registered");
        }
        Role role = requireRole(req.roleCode());
        UUID storeId = resolveStore(role, req.storeId());
        Instant expiresAt = requireFuture(req.expiresAt());
        // Creating staff mints an assignment, so it must pass the same V052
        // privilege ladder as addRoleAssignment (no escalation, no GLOBAL role
        // without role:privileged-assign).
        rbacPolicy.checkAssign(actor, role, null, storeId);

        User u = User.register(req.fullName(), req.email(), language);
        u.updateProfile(req.fullName(), blankToNull(req.phone()), language);
        User saved = users.save(u);
        credentials.save(new UserCredential(saved.id(), passwords.encode(req.password())));
        assignments.save(newAssignment(saved.id(), role.id(), storeId, expiresAt, actor.userId()));

        StaffResponse response = detail(saved.id());
        audit.success(actor.userId(), storeId, "STAFF_CREATED", "IDENTITY", "USER", saved.id(), null, response,
                "role=" + role.code());
        return response;
    }

    @Transactional
    public StaffResponse update(CurrentActor actor, UUID userId, UpdateStaffRequest req) {
        User u = loadUser(userId);
        requireVersion(req.version(), u.version());
        StaffResponse before = detail(userId);

        String fullName = req.fullName() != null ? req.fullName() : u.fullName();
        String phone = req.phone() != null ? req.phone() : u.phone();
        String language = req.preferredLanguage() != null ? LocaleNormalizer.normalize(req.preferredLanguage())
                : u.preferredLanguage();
        u.updateProfile(fullName, phone, language);
        users.saveAndFlush(u);

        StaffResponse after = detail(userId);
        audit.success(actor.userId(), null, "STAFF_UPDATED", "IDENTITY", "USER", userId, before, after, null);
        return after;
    }

    @Transactional
    public StaffResponse changeStatus(CurrentActor actor, UUID userId, StaffStatusRequest req) {
        User u = loadUser(userId);
        requireVersion(req.version(), u.version());
        StaffResponse before = detail(userId);

        UserStatus target = req.active() ? UserStatus.ACTIVE : UserStatus.DISABLED;
        if (target == UserStatus.DISABLED) {
            // Never allow the organisation to be locked out of administration.
            if (isActiveGlobalAdmin(userId) && staff.countActiveGlobalAdmins() <= 1) {
                throw new ForbiddenException("LAST_ADMIN_PROTECTED",
                        "Cannot disable the final active administrator");
            }
            // V052: the last active SUPER_ADMIN is protected even if another
            // global ADMIN would remain (rule 5 of the hardening spec).
            rbacPolicy.runLastSuperAdminGuarded(() -> {
                u.changeStatus(target);
                // Invalidate outstanding access tokens and drop refresh sessions so a
                // disabled account cannot continue or re-authenticate (Master §6.5).
                u.incrementSecurityVersion();
                sessions.revokeAll(userId);
                users.saveAndFlush(u);
            });
        } else {
            u.changeStatus(target);
            users.saveAndFlush(u);
        }

        StaffResponse after = detail(userId);
        audit.success(actor.userId(), null, req.active() ? "STAFF_ACTIVATED" : "STAFF_DISABLED", "IDENTITY", "USER",
                userId, before, after, req.reason());
        return after;
    }

    @Transactional
    public StaffResponse addRoleAssignment(CurrentActor actor, UUID userId, RoleAssignmentRequest req) {
        User u = loadUser(userId);
        Role role = requireRole(req.roleCode());
        UUID storeId = resolveStore(role, req.storeId());
        Instant expiresAt = requireFuture(req.expiresAt());
        // V052 privilege ladder: staff:role-assign baseline, role:privileged-assign
        // for GLOBAL roles, no escalation, no self role changes, store in scope.
        rbacPolicy.checkAssign(actor, role, userId, storeId);

        boolean duplicate = assignments.findByUserIdAndRoleId(userId, role.id()).stream()
                .anyMatch(a -> a.active() && java.util.Objects.equals(a.storeId(), storeId));
        if (duplicate) {
            throw new ConflictException("ASSIGNMENT_EXISTS", "That role is already assigned for this scope");
        }
        assignments.save(newAssignment(userId, role.id(), storeId, expiresAt, actor.userId()));
        // Changing authority must invalidate tokens minted under the old set.
        u.incrementSecurityVersion();
        users.saveAndFlush(u);

        StaffResponse after = detail(userId);
        audit.success(actor.userId(), storeId, "STAFF_ROLE_ASSIGNED", "IDENTITY", "USER", userId, null, after,
                "role=" + role.code());
        return after;
    }

    @Transactional
    public StaffResponse revokeRoleAssignment(CurrentActor actor, UUID userId, UUID assignmentId, String reason) {
        User u = loadUser(userId);
        UserRoleAssignment a = assignments.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ASSIGNMENT_NOT_FOUND", "Assignment not found"));
        if (!a.userId().equals(userId)) {
            throw new ResourceNotFoundException("ASSIGNMENT_NOT_FOUND", "Assignment not found");
        }
        if (a.revokedAt() != null) {
            throw new ConflictException("ASSIGNMENT_REVOKED", "Assignment is already revoked");
        }
        Role role = roles.findById(a.roleId())
                .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "Role not found"));
        rbacPolicy.checkRevoke(actor, role, userId, a.storeId());
        // Lock the SUPER_ADMIN role row, apply the revocation, then recount the
        // active holders — reaching zero aborts this transaction with 409 (V052).
        rbacPolicy.runLastSuperAdminGuarded(() -> {
            a.revoke(actor.userId(), reason);
            assignments.saveAndFlush(a);
            u.incrementSecurityVersion();
            users.saveAndFlush(u);
        });

        StaffResponse after = detail(userId);
        audit.success(actor.userId(), a.storeId(), "STAFF_ROLE_REVOKED", "IDENTITY", "USER", userId, null, after,
                reason);
        return after;
    }

    // ---------------------------------------------------------------- helpers

    private User loadUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND",
                "Staff member not found"));
    }

    private Role requireRole(String roleCode) {
        return roles.findByCode(roleCode)
                .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "Role not found"));
    }

    /**
     * Enforces the scope contract from the role's OWN metadata since V052:
     * role_scope GLOBAL binds store_id NULL; role_scope STORE binds an existing
     * active branch. This replaces the former hard-coded admin-role-name list
     * (audit finding F5) and keeps CUSTOMER globally unbound.
     */
    private UUID resolveStore(Role role, UUID storeId) {
        if (role.roleScope() == RoleScope.GLOBAL) {
            if (storeId != null) {
                throw new ValidationException("GLOBAL_ROLE_STORE_CONFLICT",
                        "Global-scope roles must not be bound to a branch");
            }
            return null;
        }
        if (storeId == null) {
            throw new ValidationException("STORE_REQUIRED", "This role must be assigned to a branch");
        }
        if (!staff.storeExists(storeId)) {
            throw new ResourceNotFoundException("STORE_NOT_FOUND", "Branch not found");
        }
        return storeId;
    }

    private Instant requireFuture(Instant when) {
        if (when != null && !when.isAfter(Instant.now())) {
            throw new ValidationException("EXPIRY_IN_PAST", "Expiry must be in the future");
        }
        return when;
    }

    private UserRoleAssignment newAssignment(UUID userId, UUID roleId, UUID storeId, Instant expiresAt) {
        return newAssignment(userId, roleId, storeId, expiresAt, null);
    }

    private UserRoleAssignment newAssignment(UUID userId, UUID roleId, UUID storeId, Instant expiresAt,
            UUID assignedBy) {
        UserRoleAssignment a = UserRoleAssignment.assign(userId, roleId, storeId, assignedBy);
        if (expiresAt != null) {
            a.expireAt(expiresAt);
        }
        return a;
    }

    private boolean isActiveGlobalAdmin(UUID userId) {
        Instant now = Instant.now();
        List<StaffAssignmentView> rows = mapAssignments(staff.assignmentsByUserIds(List.of(userId)), now);
        return rows.stream().anyMatch(x -> x.active() && x.storeId() == null
                && GLOBAL_ADMIN_ROLES.contains(x.roleCode()));
    }

    private void requireVersion(long expected, long current) {
        if (expected != current) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This record was updated by another user. Refresh before editing again.");
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private Sort buildSort(String sort) {
        String field = (sort == null || !SORTABLE.contains(sort)) ? "createdAt" : sort;
        return Sort.by(Sort.Direction.DESC, field);
    }

    private Map<UUID, List<StaffAssignmentView>> groupAssignments(List<StaffAssignmentProjection> rows, Instant now) {
        Map<UUID, List<StaffAssignmentView>> grouped = new HashMap<>();
        for (StaffAssignmentProjection r : rows) {
            grouped.computeIfAbsent(r.getUserId(), k -> new ArrayList<>()).add(toView(r, now));
        }
        return grouped;
    }

    private List<StaffAssignmentView> mapAssignments(List<StaffAssignmentProjection> rows, Instant now) {
        return rows.stream().map(r -> toView(r, now)).sorted(Comparator.comparing(StaffAssignmentView::assignedAt)
                .reversed()).toList();
    }

    private List<StaffAssignmentView> activeOnly(List<StaffAssignmentView> views) {
        return views == null ? List.of() : views.stream().filter(StaffAssignmentView::active).toList();
    }

    private StaffAssignmentView toView(StaffAssignmentProjection p, Instant now) {
        boolean active = p.getRevokedAt() == null && (p.getExpiresAt() == null || p.getExpiresAt().isAfter(now));
        return new StaffAssignmentView(p.getAssignmentId(), p.getRoleCode(), p.getRoleName(), p.getStoreId(),
                p.getStoreName(), p.getAssignedAt(), p.getExpiresAt(), p.getRevokedAt(), active);
    }

    private StaffResponse toResponse(StaffUserProjection u, List<StaffAssignmentView> views, boolean includeAll) {
        List<String> roleCodes = views.stream().filter(StaffAssignmentView::active).map(StaffAssignmentView::roleCode)
                .distinct().sorted().toList();
        return new StaffResponse(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getPreferredLanguage(),
                u.getStatus(), roleCodes, views, u.getCreatedAt(), u.getUpdatedAt(), u.getVersion());
    }
}
