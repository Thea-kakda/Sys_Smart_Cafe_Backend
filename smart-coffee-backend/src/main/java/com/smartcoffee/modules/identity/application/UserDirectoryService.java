package com.smartcoffee.modules.identity.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.identity.api.ManagementUserDetail;
import com.smartcoffee.modules.identity.api.ManagementUserSummary;
import com.smartcoffee.modules.identity.api.StaffAssignmentView;
import com.smartcoffee.modules.identity.persistence.StaffQueryRepository;
import com.smartcoffee.modules.identity.persistence.StaffQueryRepository.StaffAssignmentProjection;
import com.smartcoffee.modules.identity.persistence.UserDirectoryQueryRepository;
import com.smartcoffee.modules.identity.persistence.UserDirectoryQueryRepository.CustomerDirectoryView;
import com.smartcoffee.modules.identity.persistence.UserDirectoryQueryRepository.DirectoryUserProjection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User Directory read service (Full-stack wiring §1.1). Authorization is the
 * controller-level user:admin-read (global-administrative): the directory
 * intentionally spans every store's users, so — unlike the staff roster — there
 * is no per-store scoping; the personal-data exposure is contained by granting
 * the code only to SUPER_ADMIN/ADMIN (V028). The service owns input hardening:
 * bounded page size, sentinel-normalized filters, a validated account-type
 * enum, and batched assignment loading (one IN-query per page, never N+1).
 */
@SuppressWarnings("null")
@Service
public class UserDirectoryService {

    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final Set<String> ACCOUNT_TYPES = Set.of("ALL", "STAFF", "CUSTOMER");

    private final UserDirectoryQueryRepository directory;
    private final StaffQueryRepository assignments;

    public UserDirectoryService(UserDirectoryQueryRepository directory, StaffQueryRepository assignments) {
        this.directory = directory;
        this.assignments = assignments;
    }

    @Transactional(readOnly = true)
    public Page<ManagementUserSummary> list(CurrentActor actor, String search, String status, String roleCode,
            UUID storeId, String accountType, int page, int size) {
        // actor is unused for scoping (global privilege) but kept in the signature:
        // every management read service takes the caller, so a future narrowing
        // (e.g. store-scoped customer support roles) is a service-local change.
        Instant now = Instant.now();
        String term = search == null ? "" : search.trim();
        String type = accountType == null || accountType.isBlank() ? "ALL" : accountType.trim().toUpperCase();
        if (!ACCOUNT_TYPES.contains(type)) {
            throw new ValidationException("accountType must be one of ALL, STAFF, CUSTOMER");
        }
        boolean statusFilter = notBlank(status);
        boolean roleFilter = notBlank(roleCode);
        boolean storeFilter = storeId != null;
        String storeIdText = (storeId != null) ? storeId.toString() : NIL_UUID.toString();

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);

        Page<DirectoryUserProjection> rows = directory.search(term, statusFilter,
                statusFilter ? status.trim().toUpperCase() : "", roleFilter,
                roleFilter ? roleCode.trim().toUpperCase() : "", storeFilter,
                storeIdText, type, now,
                PageRequest.of(safePage, safeSize));

        // Batch-load active role codes for exactly the users on this page.
        List<UUID> userIds = rows.getContent().stream().map(DirectoryUserProjection::getUserId).toList();
        Map<UUID, List<String>> rolesByUser = userIds.isEmpty() ? Map.of()
                : assignments.assignmentsByUserIds(userIds).stream()
                        .filter(a -> isActive(a, now))
                        .collect(Collectors.groupingBy(StaffAssignmentProjection::getUserId,
                                Collectors.mapping(StaffAssignmentProjection::getRoleCode, Collectors.toList())));

        List<ManagementUserSummary> content = rows.getContent().stream()
                .map(r -> toSummary(r, rolesByUser.getOrDefault(r.getUserId(), List.of())))
                .toList();
        return new PageImpl<>(content, PageRequest.of(safePage, safeSize), rows.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ManagementUserDetail detail(CurrentActor actor, UUID userId) {
        Instant now = Instant.now();
        DirectoryUserProjection row = directory.findUser(userId);
        if (row == null) {
            throw new ResourceNotFoundException("USER_NOT_FOUND", "User not found");
        }
        List<StaffAssignmentView> history = assignments.assignmentsByUserIds(List.of(userId)).stream()
                .map(a -> toAssignmentView(a, now)).toList();
        List<String> activeRoles = history.stream().filter(StaffAssignmentView::active)
                .map(StaffAssignmentView::roleCode).toList();
        CustomerDirectoryView customer = directory.customerView(userId).stream().findFirst().orElse(null);
        ManagementUserDetail.CustomerProfileView customerView = customer == null ? null
                : new ManagementUserDetail.CustomerProfileView(customer.getCustomerId(), customer.getLoyaltyNumber(),
                        customer.getPreferredStoreId(), customer.getPreferredStoreName(), customer.getBirthDate(),
                        customer.isMarketingOptIn(), customer.getCustomerSince());
        return new ManagementUserDetail(toSummary(row, activeRoles), history, customerView);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isActive(StaffAssignmentProjection a, Instant now) {
        return a.getRevokedAt() == null && (a.getExpiresAt() == null || a.getExpiresAt().isAfter(now));
    }

    private static ManagementUserSummary toSummary(DirectoryUserProjection r, List<String> roles) {
        return new ManagementUserSummary(r.getUserId(), r.getFullName(), r.getEmail(), r.getPhone(),
                r.getPreferredLanguage(), r.getStatus(), roles, r.isCustomer(), r.getCreatedAt(), r.getUpdatedAt(),
                r.getVersion());
    }

    private static StaffAssignmentView toAssignmentView(StaffAssignmentProjection a, Instant now) {
        return new StaffAssignmentView(a.getAssignmentId(), a.getRoleCode(), a.getRoleName(), a.getStoreId(),
                a.getStoreName(), a.getAssignedAt(), a.getExpiresAt(), a.getRevokedAt(), isActive(a, now));
    }
}
