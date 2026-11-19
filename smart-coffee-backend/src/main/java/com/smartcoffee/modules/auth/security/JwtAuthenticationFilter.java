package com.smartcoffee.modules.auth.security;

import com.smartcoffee.modules.identity.domain.UserStatus;
import com.smartcoffee.modules.identity.persistence.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer access token and, critically, compares its {@code sec_ver}
 * claim against the persisted {@code identity.users.security_version}. A mismatch
 * (or an unknown user, or one that is no longer ACTIVE) means the token is no longer
 * trustworthy: it was minted before a security event such as logout-all, disable or
 * lock, so authentication is not placed in the context and the request surfaces as
 * 401 downstream. Authorities from the {@code permissions} claim are attached for
 * completeness; authoritative authorization is re-resolved per-request by
 * CurrentActorProvider against the database.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtDecoder decoder;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtDecoder decoder, UserRepository users) {
        this.decoder = decoder;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Jwt jwt = decoder.decode(header.substring(7));
                UUID userId = UUID.fromString(jwt.getSubject());
                Number tokenSecVer = jwt.getClaim("sec_ver");
                var user = users.findById(userId);
                if (user.isPresent() && user.get().status() == UserStatus.ACTIVE
                        && tokenSecVer != null
                        && user.get().securityVersion() == tokenSecVer.longValue()) {
                    List<String> ps = jwt.getClaimAsStringList("permissions");
                    var auth = new UsernamePasswordAuthenticationToken(jwt.getSubject(), null,
                            ps == null ? List.of() : ps.stream().map(SimpleGrantedAuthority::new).toList());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
                // else: sec_ver mismatch / unknown user -> leave the context anonymous (401).
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
