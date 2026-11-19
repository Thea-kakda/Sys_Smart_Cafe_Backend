package com.smartcoffee.modules.auth.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.auth.application.AuthenticationService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth";
    private final AuthenticationService service;
    private final CurrentActorProvider actor;
    /**
     * Refresh-cookie attributes are externalized (Master Prompt §5.8). {@code secure}
     * defaults to false so local HTTP development works, and MUST be true behind HTTPS
     * in production. The same flags drive both issuance and clearance so a cookie the
     * browser stored is always the cookie we can remove.
     */
    @Value("${app.security.cookie.secure:false}")
    private boolean cookieSecure;
    @Value("${app.security.cookie.same-site:Lax}")
    private String cookieSameSite;

    public AuthController(AuthenticationService s, CurrentActorProvider a) {
        service = s;
        actor = a;
    }

    @PostMapping("/register")
    ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest req,
            HttpServletResponse res) {
        var r = service.register(req);
        cookie(res, r);
        return ResponseEntity.status(201).body(ApiResponse.ok(r.response(), CorrelationContext.requestId()));
    }

    @PostMapping("/login")
    ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletResponse res) {
        var r = service.login(req);
        cookie(res, r);
        return ApiResponse.ok(r.response(), CorrelationContext.requestId());
    }

    @PostMapping("/refresh")
    ApiResponse<AuthResponse> refresh(@CookieValue(COOKIE) String token, HttpServletResponse res) {
        var r = service.refresh(token);
        cookie(res, r);
        return ApiResponse.ok(r.response(), CorrelationContext.requestId());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String token, HttpServletResponse res) {
        service.logout(token);
        clear(res);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(HttpServletResponse res) {
        service.logoutAll(actor.require().userId());
        clear(res);
        return ResponseEntity.noContent().build();
    }

    private void cookie(HttpServletResponse res, AuthenticationService.Result r) {
        ResponseCookie c = ResponseCookie.from(COOKIE, r.refreshToken()).httpOnly(true).secure(cookieSecure)
                .sameSite(cookieSameSite).path(COOKIE_PATH)
                .maxAge(Duration.between(java.time.Instant.now(), r.refreshExpiresAt())).build();
        res.addHeader(HttpHeaders.SET_COOKIE, c.toString());
    }

    private void clear(HttpServletResponse res) {
        // Clearance mirrors the issuance attributes exactly (name, path, httpOnly,
        // secure, sameSite) or the browser would keep the original cookie and the
        // user would remain silently signed in after logout.
        res.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(COOKIE, "").httpOnly(true).secure(cookieSecure).sameSite(cookieSameSite)
                        .path(COOKIE_PATH).maxAge(0).build().toString());
    }
}

