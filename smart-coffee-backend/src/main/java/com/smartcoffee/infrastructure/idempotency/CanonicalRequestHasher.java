package com.smartcoffee.infrastructure.idempotency;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;


import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class CanonicalRequestHasher {

    private final ObjectMapper objectMapper;

    public CanonicalRequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(Object request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }

        try {
            byte[] canonicalJson = objectMapper.writeValueAsBytes(request);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(canonicalJson)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }

    public String hashJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON cannot be null or blank");
        }

        try {
            Object parsedJson = objectMapper.readValue(
                    json,
                    Object.class
            );

            byte[] canonicalJson = objectMapper.writeValueAsBytes(parsedJson);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(canonicalJson)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }
}
