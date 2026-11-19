package com.smartcoffee.modules.auth.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final long accessTokenMinutes;
    private final long refreshTokenDays;

    public JwtTokenService(JwtProperties properties) {
        byte[] secretBytes = decodeSecret(properties.secret());

        if (secretBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must contain at least 32 bytes for HS256");
        }

        SecretKey secretKey =
                new SecretKeySpec(secretBytes, "HmacSHA256");

        this.jwtEncoder = NimbusJwtEncoder
                .withSecretKey(secretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
        
        this.accessTokenMinutes = properties.accessTokenMinutes();
        this.refreshTokenDays = properties.refreshTokenDays();
    }

    private static byte[] decodeSecret(String encodedSecret) {
        try {
            return Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            return encodedSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String generateToken(String subject) {
        return createToken(subject, accessTokenMinutes);
    }

    public Duration refreshTtl() {
        return Duration.ofDays(refreshTokenDays);
    }

    public String access(UUID userId, long securityVersion, Set<?> permissions) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("smart-coffee-api")
                .subject(userId.toString())
                .claim("sec_ver", securityVersion)
                .claim("permissions", permissions)
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenMinutes, ChronoUnit.MINUTES))
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        return jwtEncoder
                .encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();
    }

    private String createToken(String subject, long validityMinutes) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("smart-coffee-api")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(validityMinutes, ChronoUnit.MINUTES))
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        return jwtEncoder
                .encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();
    }
}