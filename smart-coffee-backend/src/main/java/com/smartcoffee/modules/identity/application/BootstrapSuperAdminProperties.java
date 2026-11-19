package com.smartcoffee.modules.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap Super Admin configuration (Identity & Access hardening). Values are
 * bound ONLY from {@code app.bootstrap.superadmin.*}, which application.yml maps
 * from the BOOTSTRAP_SUPERADMIN_* environment variables. Nothing is hard-coded;
 * when {@code enabled} is false (the default) or a value is blank the runner does
 * nothing. The password is never logged.
 */
@ConfigurationProperties(prefix = "app.bootstrap.superadmin")
public record BootstrapSuperAdminProperties(
        boolean enabled,
        String fullName,
        String email,
        String password) {
}
