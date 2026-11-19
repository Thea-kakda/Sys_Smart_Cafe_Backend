package com.smartcoffee.modules.customer.application;

import com.smartcoffee.common.exception.InfrastructureException;
import com.smartcoffee.modules.rbac.domain.Role;
import com.smartcoffee.modules.rbac.domain.UserRoleAssignment;
import com.smartcoffee.modules.rbac.persistence.RoleRepository;
import com.smartcoffee.modules.rbac.persistence.UserRoleAssignmentRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants the global (store_id NULL) CUSTOMER role to a freshly registered user.
 * This is the SINGLE code path that creates a CUSTOMER assignment: it uses the
 * RBAC JPA repositories only and never touches identity.user_role_assignments
 * via raw SQL. customer_profiles provisioning remains owned exclusively by
 * {@link CustomerProfileService#ensureProfile(UUID)}, so no path inserts a
 * profile row a second time.
 */
@Service
public class CustomerRegistrationService {
    private final RoleRepository roles;
    private final UserRoleAssignmentRepository assignments;

    public CustomerRegistrationService(RoleRepository roles, UserRoleAssignmentRepository assignments) {
        this.roles = roles;
        this.assignments = assignments;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(UUID userId) {
        Role customer = roles.findByCode("CUSTOMER")
                .orElseThrow(() -> new InfrastructureException("CUSTOMER_ROLE_NOT_CONFIGURED",
                        "CUSTOMER role is not configured"));
        // Registration creates brand-new users, so no active global CUSTOMER grant
        // can pre-exist. The guard keeps the call idempotent if ever re-invoked and
        // respects the ux_active_global_user_role unique partial index (V002).
        if (assignments.findActiveGlobal(userId, customer.id(), Instant.now()).isEmpty()) {
            assignments.save(UserRoleAssignment.assign(userId, customer.id(), null));
        }
    }
}
