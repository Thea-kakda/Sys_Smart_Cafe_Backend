package com.smartcoffee.infrastructure.realtime;

import java.util.List;
import java.util.Map;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authenticates the STOMP WebSocket handshake (Checkpoint 13).
 *
 * <p>A browser cannot set an {@code Authorization} header on a WebSocket
 * upgrade, and the security filter chain is stateless (header-only JWT). So the
 * POS/dashboard passes the SAME short-lived access token as a query parameter
 * ({@code /ws?access_token=...}); this interceptor verifies it with the
 * application's own {@link JwtDecoder} and stores the resulting
 * {@link org.springframework.security.core.Authentication} in the handshake
 * attributes, which Spring propagates into the WebSocket session. The token is
 * validated exactly like an HTTP bearer token — an unsigned/expired token yields
 * no principal, so a subsequent SUBSCRIBE to a protected store topic is rejected
 * by {@link WebSocketAuthorizationInterceptor}. No authority is granted here
 * beyond what the verified JWT already carries.
 */
@Component
public class WebSocketAuthHandshakeInterceptor implements HandshakeInterceptor {

    /** Session-attribute key holding the verified {@code Authentication}. */
    public static final String AUTH_ATTRIBUTE = "smartcoffee.ws.authentication";

    private final JwtDecoder jwtDecoder;

    public WebSocketAuthHandshakeInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = accessTokenFromQuery(request.getURI().getRawQuery());
        if (token != null && !token.isBlank()) {
            try {
                Jwt jwt = jwtDecoder.decode(token);
                List<String> permissions = jwt.getClaimAsStringList("permissions");
                var authorities = (permissions == null ? List.<String>of() : permissions).stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(jwt.getSubject(), null, authorities);
                attributes.put(AUTH_ATTRIBUTE, authentication);
            } catch (Exception ignored) {
                // Invalid token: proceed unauthenticated. Protected subscriptions
                // are refused downstream; public connections simply receive nothing.
                attributes.remove(AUTH_ATTRIBUTE);
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static String accessTokenFromQuery(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "access_token".equals(pair.substring(0, eq))) {
                return org.springframework.util.StringUtils.uriDecode(pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
