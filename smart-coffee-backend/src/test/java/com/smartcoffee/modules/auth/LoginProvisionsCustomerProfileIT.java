package com.smartcoffee.modules.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.modules.auth.api.LoginRequest;
import com.smartcoffee.modules.auth.api.RegisterRequest;
import com.smartcoffee.modules.auth.application.AuthenticationService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Checkpoint 11 blocker fix — the customer-centric cart/checkout/order/payment
 * machinery is keyed by a customer profile, but no live path ever created one,
 * so even a valid POS cashier login died with "Customer profile not found".
 * These tests prove that minting a session now provisions (idempotently) a
 * backing profile for ANY authenticated principal, without touching RBAC.
 */
class LoginProvisionsCustomerProfileIT extends IntegrationTestBase {

    @Autowired
    AuthenticationService auth;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PasswordEncoder passwords;

    private int profileCount(UUID userId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from customer.customer_profiles where user_id = ?::uuid", Integer.class,
                userId.toString());
        return n == null ? 0 : n;
    }

    @Test
    void registerProvisionsBackingProfile() {
        String email = "reg-" + UUID.randomUUID() + "@smart.coffee";
        var result = auth.register(new RegisterRequest("Registering Guest", email, "Str0ng!Pass", "en-US"));
        assertNotNull(result.response().accessToken());
        assertEquals(1, profileCount(result.response().userId()),
                "self-registration must create exactly one customer profile");
    }

    @Test
    void loginProvisionsMissingProfileForStaffAndIsIdempotent() {
        // Simulate a staff account seeded WITHOUT a customer profile (the POS cashier case).
        UUID userId = UUID.randomUUID();
        String email = "cashier-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "POS Cashier", email, email.toLowerCase(), "en-US");
        jdbc.update("insert into identity.user_credentials (user_id, password_hash, failed_login_count, "
                + "password_changed_at) values (?,?::text,0,now())",
                userId, passwords.encode("Str0ng!Pass"));

        assertEquals(0, profileCount(userId), "fixture must start with no profile");

        var first = auth.login(new LoginRequest(email, "Str0ng!Pass"));
        assertNotNull(first.response().accessToken());
        assertEquals(1, profileCount(userId), "first login provisions the missing profile");

        auth.login(new LoginRequest(email, "Str0ng!Pass"));
        assertEquals(1, profileCount(userId), "repeat login must not duplicate the profile");

        // Login must not fabricate any role assignment for the cashier.
        Integer roles = jdbc.queryForObject(
                "select count(*) from identity.user_role_assignments where user_id = ?::uuid", Integer.class,
                userId.toString());
        assertEquals(0, roles, "profile provisioning must not alter RBAC");
    }

    @Test
    void failedLoginDoesNotProvisionProfile() {
        UUID userId = UUID.randomUUID();
        String email = "ghost-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Ghost User", email, email.toLowerCase(), "en-US");
        jdbc.update("insert into identity.user_credentials (user_id, password_hash, failed_login_count, "
                + "password_changed_at) values (?,?::text,0,now())",
                userId, passwords.encode("Str0ng!Pass"));

        assertThrows(RuntimeException.class, () -> auth.login(new LoginRequest(email, "Wrong!Pass123")));
        assertEquals(0, profileCount(userId), "an unauthenticated attempt must not provision a profile");
    }
}
