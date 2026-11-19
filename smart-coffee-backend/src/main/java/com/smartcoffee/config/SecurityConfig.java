package com.smartcoffee.config;

import java.util.Base64;
import java.util.Arrays;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import com.smartcoffee.modules.auth.security.JwtAuthenticationFilter;
import com.smartcoffee.modules.auth.security.JwtProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@SuppressWarnings("null")
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * Explicit CORS (Master Prompt §5.9). Credentials are enabled because the refresh
     * cookie and Authorization header must cross the Dashboard/POS/Customer origins,
     * so a wildcard origin is deliberately NOT used — the browser rejects credentialed
     * requests to "*" anyway. Origins are supplied as a comma-separated list from
     * configuration; only exact matches are allowed, with the correlation header
     * X-Request-Id both accepted and exposed.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.security.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Idempotency-Key is a request header on cart mutations (Slice 2C), so a
        // browser preflight must be allowed to carry it; X-Request-Id is accepted
        // and echoed for correlation.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        byte[] secretBytes = Base64.getDecoder()
                .decode(properties.secret());

        SecretKeySpec secretKey = new SecretKeySpec(
                secretBytes,
                "HmacSHA256"
        );

        return NimbusJwtDecoder
                .withSecretKey(secretKey)
                .build();
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            JwtAuthenticationFilter jwt
    ) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                // An absent/invalid bearer token (including a rejected sec_ver) must
                // surface as 401 Unauthorized, not the default 403, so clients can
                // distinguish "sign in again" from "authenticated but forbidden".
                // AccessDeniedException for an authenticated caller still maps to 403.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/api/v1/auth/**",
                                "/api/v1/stores/**",
                                // STOMP upgrade (Checkpoint 13): a browser cannot send an
                                // Authorization header on a WebSocket handshake, so the
                                // upgrade is permitted here and the access token rides as
                                // a query param verified by WebSocketAuthHandshakeInterceptor.
                                // Data is still gated per-frame by the STOMP channel
                                // interceptor (store access + permission), and the
                                // authoritative queue is only ever read over the normal
                                // authenticated REST endpoints.
                                "/ws"
                        )
                        .permitAll()
                        // Public catalog BROWSING stays open (POS/Customer read the
                        // product detail and category list without a token), but only
                        // for GET. Product/category writes (POST/PATCH) fall through to
                        // .authenticated() below and are then gated service-layer by
                        // product:manage / category:manage. The CUSTOMER_APP catalog is
                        // the same public read surface reached through a dedicated,
                        // server-owned route (Slice 3B.1 §2), so GET on /api/v1/app/**
                        // is open for browsing exactly like the web catalog; it can only
                        // ever resolve CUSTOMER_APP and exposes no management capability.
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/categories/**",
                                "/api/v1/app/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated()
                )
                .addFilterBefore(
                        jwt,
                        UsernamePasswordAuthenticationFilter.class
                )
                .build();
    }
}