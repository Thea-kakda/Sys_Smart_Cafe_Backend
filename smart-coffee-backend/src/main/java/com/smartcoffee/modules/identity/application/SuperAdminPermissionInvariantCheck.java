package com.smartcoffee.modules.identity.application;

import com.smartcoffee.modules.rbac.persistence.RolePermissionManagementRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * D4 invariant safety net (V052): SUPER_ADMIN must hold EVERY permission. The
 * V052 backfill repairs the table, but a FUTURE migration could seed a new
 * permission without the SUPER_ADMIN cross-join — so after every startup the
 * check re-scans the invariant and logs an explicit ERROR listing the orphans.
 * The project has no readiness-probe mechanism to fail (Gate G17), so the
 * ERROR log plus the CI invariant test are the enforcement pair.
 */
@Component
public class SuperAdminPermissionInvariantCheck {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminPermissionInvariantCheck.class);

    private final RolePermissionManagementRepository grants;

    public SuperAdminPermissionInvariantCheck(RolePermissionManagementRepository grants) {
        this.grants = grants;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void check() {
        List<String> missing = grants.missingSuperAdminCodes();
        if (missing.isEmpty()) {
            log.info("RBAC invariant OK: every permission is granted to SUPER_ADMIN");
            return;
        }
        log.error("RBAC INVARIANT VIOLATION: {} permission(s) lack a SUPER_ADMIN grant: {}",
                missing.size(), missing);
    }
}
