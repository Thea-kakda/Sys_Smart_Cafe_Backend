package com.smartcoffee.infrastructure.storage;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object-storage safety policy bound from {@code app.storage.*}.
 *
 * <p>Everything here is configurable so business rules (allowed prefixes,
 * accepted content types, size ceiling, presigned-URL lifetime) can evolve
 * without touching code. Defaults live in {@code application.yml}.
 *
 * @param allowedPrefixes     the ONLY top-level key segments the backend will
 *                            accept (e.g. {@code products/}); the caller can
 *                            never write outside these.
 * @param allowedContentTypes MIME types accepted on upload.
 * @param maxFileSizeBytes    hard ceiling on a single object's size.
 * @param signedUrlTtlSeconds default lifetime of a generated presigned GET URL.
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        List<String> allowedPrefixes,
        List<String> allowedContentTypes,
        long maxFileSizeBytes,
        long signedUrlTtlSeconds) {
}
