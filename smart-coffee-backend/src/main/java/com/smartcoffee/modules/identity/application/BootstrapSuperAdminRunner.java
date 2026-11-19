package com.smartcoffee.modules.identity.application;

import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.identity.domain.*;
import com.smartcoffee.modules.identity.persistence.*;
import com.smartcoffee.modules.rbac.domain.Role;
import com.smartcoffee.modules.rbac.domain.UserRoleAssignment;

import com.smartcoffee.modules.rbac.persistence.RoleRepository;
import com.smartcoffee.modules.rbac.persistence.UserRoleAssignmentRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds exactly one Super Admin at startup when explicitly enabled
 * (app.bootstrap.superadmin.enabled=true), fully driven by the
 * BOOTSTRAP_SUPERADMIN_* environment variables. Idempotent: a second run with
 * the same email never overwrites the password or other roles — it only ensures
 * the global SUPER_ADMIN assignment exists. Runs in a single transaction and
 * writes an audit entry. The password and its hash are NEVER logged.
 */
@Component
@EnableConfigurationProperties(BootstrapSuperAdminProperties.class)
public class BootstrapSuperAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapSuperAdminRunner.class);
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";
    /** Register-equivalent length rule (see RegisterRequest). */
    private static final int MIN_PASSWORD = 10, MAX_PASSWORD = 128;
    /** Only a soft sanity check — we warn, we never rewrite the operator's email. */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");

    private final BootstrapSuperAdminProperties config;
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final RoleRepository roles;
    private final UserRoleAssignmentRepository assignments;
    private final PasswordEncoder passwords;
    private final AuditWriter audit;

    public BootstrapSuperAdminRunner(BootstrapSuperAdminProperties config, UserRepository users,
            UserCredentialRepository credentials, RoleRepository roles, UserRoleAssignmentRepository assignments,
            PasswordEncoder passwords, AuditWriter audit) {
        this.config = config;
        this.users = users;
        this.credentials = credentials;
        this.roles = roles;
        this.assignments = assignments;
        this.passwords = passwords;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!config.enabled()) {
            return;
        }
        String fullName = trim(config.fullName());
        String rawEmail = trim(config.email());
        String password = config.password();
        if (fullName.isEmpty() || rawEmail.isEmpty() || password == null || password.isEmpty()) {
            log.warn("bootstrap.superadmin.enabled=true but one of full-name/email/password is missing; skipping");
            return;
        }
        if (password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new IllegalStateException(
                    "Bootstrap super admin password must be " + MIN_PASSWORD + ".." + MAX_PASSWORD + " characters");
        }
        String normalized = rawEmail.toLowerCase();
        if (!EMAIL_SHAPE.matcher(rawEmail).matches()) {
            log.warn("bootstrap super admin email '{}' does not look valid (check for a typo); using it as-is", rawEmail);
        }

        Role superAdmin = roles.findByCode(SUPER_ADMIN_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "Bootstrap enabled but role '" + SUPER_ADMIN_ROLE + "' is not seeded"));

        User user = users.findByNormalizedEmail(normalized).orElse(null);
        if (user != null) {
            // Never overwrite an existing account's password or roles.
            ensureAssignment(user.id(), superAdmin.id());
            log.info("bootstrap super admin already present; ensured SUPER_ADMIN assignment (userId={})", user.id());
            return;
        }

        user = users.save(User.register(fullName, rawEmail, "en"));
        credentials.save(new UserCredential(user.id(), passwords.encode(password)));
        assignments.save(UserRoleAssignment.assign(user.id(), superAdmin.id(), null));
        audit.system(null, "SUPERADMIN_BOOTSTRAP", "SECURITY", "User", user.id(), null,
                Map.of("userId", user.id().toString(), "role", SUPER_ADMIN_ROLE), "system.bootstrap");
        log.info("bootstrap super admin created (userId={}, name='{}')", user.id(), fullName);
    }

    private void ensureAssignment(UUID userId, UUID roleId) {
        if (assignments.findActiveGlobal(userId, roleId, Instant.now()).isEmpty()) {
            assignments.save(UserRoleAssignment.assign(userId, roleId, null));
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
