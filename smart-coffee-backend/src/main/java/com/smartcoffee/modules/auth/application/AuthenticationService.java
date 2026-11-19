package com.smartcoffee.modules.auth.application;

import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.exception.*;
import com.smartcoffee.common.i18n.LocaleNormalizer;
import com.smartcoffee.modules.auth.api.*;
import com.smartcoffee.modules.auth.domain.AuthSession;
import com.smartcoffee.modules.auth.persistence.AuthSessionRepository;
import com.smartcoffee.modules.auth.security.*;
import com.smartcoffee.modules.customer.application.CustomerProfileService;
import com.smartcoffee.modules.customer.application.CustomerRegistrationService;
import com.smartcoffee.modules.identity.domain.*;
import com.smartcoffee.modules.identity.persistence.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@SuppressWarnings("null")
@Service
public class AuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);
    public record Result(AuthResponse response, String refreshToken, Instant refreshExpiresAt) {
    }

    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final JwtTokenService jwt;
    private final RefreshTokenService refresh;
    private final CustomerProfileService customerProfiles;
    private final CustomerRegistrationService customerRegistrations;
    /**
     * A real, never-valid Argon2 digest used only to burn comparable CPU time on
     * the unknown-email login path so response timing does not reveal whether an
     * account exists (Master Prompt §5.7). Its value is meaningless and is never
     * compared against a genuine credential.
     */
    private final String dummyHash;

    public AuthenticationService(UserRepository u, UserCredentialRepository c, AuthSessionRepository s,
            PasswordEncoder p, JwtTokenService j, RefreshTokenService r, CustomerProfileService cp,
            CustomerRegistrationService crm) {
        users = u;
        credentials = c;
        sessions = s;
        passwords = p;
        jwt = j;
        refresh = r;
        customerProfiles = cp;
        customerRegistrations = crm;
        this.dummyHash = p.encode("invalid-placeholder-for-timing-equalization");
    }

    @Transactional
    public Result register(RegisterRequest q) {
        String email = q.email().trim().toLowerCase();
        if (users.existsByNormalizedEmail(email)) {
            // Typed 409 kept for the MVP (Master Prompt §2.4) so clients get a stable
            // code. The message stays generic about account state: we never expose
            // credential status, roles or lock state. The precise reason is logged
            // internally, keyed to the request id, without recording the email.
            log.warn("registration rejected: email already registered (requestId={})", CorrelationContext.requestId());
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "Email already registered");
        }
        // Canonicalize the locale to en-US / km-KH before it reaches the entity.
        String language = LocaleNormalizer.normalize(q.preferredLanguage());
        User u = users.save(User.register(q.fullName(), q.email(), language));
        credentials.save(new UserCredential(u.id(), passwords.encode(q.password())));
        customerProfiles.ensureProfile(u.id());
        // Single code path that grants the global CUSTOMER role (RBAC JPA repos only).
        customerRegistrations.initialize(u.id());
        return session(u);
    }

    @Transactional
    public Result login(LoginRequest q) {
        String email = q.email().trim().toLowerCase();
        User u = users.findByNormalizedEmail(email).orElse(null);
        if (u == null) {
            // Unknown email: still run a hash comparison so the wall-clock cost
            // matches a wrong-password attempt, then return the generic failure.
            passwords.matches(q.password(), dummyHash);
            throw new UnauthorizedException("Invalid credentials");
        }
        UserCredential c = credentials.findById(u.id()).orElse(null);
        if (c == null) {
            passwords.matches(q.password(), dummyHash);
            throw new UnauthorizedException("Invalid credentials");
        }
        if (!passwords.matches(q.password(), c.passwordHash())) {
            c.failure();
            throw new UnauthorizedException("Invalid credentials");
        }
        // Correct password but unavailable account: same generic response, so we
        // never enumerate whether the account is disabled or locked (Master §5.7).
        if (u.status() != UserStatus.ACTIVE || c.locked())
            throw new UnauthorizedException("Invalid credentials");
        c.success();
        customerProfiles.ensureProfile(u.id());
        return session(u);
    }

    @Transactional
    public Result refresh(String raw) {
        String hash = refresh.hash(raw);
        AuthSession old = sessions.findByRefreshTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh session"));
        if (!old.active()) {
            // Reuse of a rotated/revoked/expired token: assume the family is
            // compromised, revoke every descendant and refuse to mint a new token
            // (Master Prompt §5.6). The client must authenticate again.
            sessions.revokeFamily(old.familyId());
            log.warn("refresh token reuse detected: revoked token family (requestId={})", CorrelationContext.requestId());
            throw new UnauthorizedException("Invalid refresh session");
        }
        old.revoke();
        User u = users.findById(old.userId()).orElseThrow(() -> new UnauthorizedException("User unavailable"));
        customerProfiles.ensureProfile(u.id());
        return session(u, old.familyId());
    }

    @Transactional
    public void logout(String raw) {
        if (raw == null)
            return;
        sessions.findByRefreshTokenHash(refresh.hash(raw))
        .ifPresent(session -> session.revoke());
    }

    @Transactional
    public void logoutAll(UUID userId) {
        sessions.revokeAll(userId);
        // Bump security_version so the JWT filter rejects access tokens minted
        // before this call. NOTE: any future password-change or role-modification
        // endpoint MUST also increment security_version to achieve the same.
        users.findById(userId).ifPresent(User::incrementSecurityVersion);
    }

    private Result session(User u) {
        return session(u, UUID.randomUUID());
    }

    private Result session(User u, UUID family) {
        String raw = refresh.issue();
        Instant expiry = Instant.now().plus(jwt.refreshTtl());
        sessions.save(new AuthSession(u.id(), family, refresh.hash(raw), expiry));
        String access = jwt.access(u.id(), u.securityVersion(), Set.of());
        return new Result(new AuthResponse(u.id(), u.fullName(), u.email(), u.preferredLanguage(), access), raw,
                expiry);
    }
}

