package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.infrastructure.storage.StorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * Turns a stored catalog image object key into a short-lived presigned GET URL
 * from the private bucket.
 *
 * <p>Behavior:
 * <ul>
 *   <li>blank key → {@code null} (no image),</li>
 *   <li>already-absolute {@code http(s)} URL → returned unchanged (legacy or
 *       externally hosted images),</li>
 *   <li>otherwise → presigned URL; if the key is outside the storage policy
 *       (cannot be signed) it resolves to {@code null} rather than leaking an
 *       unusable path.</li>
 * </ul>
 *
 * <p>Presigning is a local computation (no per-call round trip to MinIO), so
 * resolving many product images in one response does not cause N+1 network
 * calls.
 */
@Component
public class DefaultCatalogImageUrlResolver implements CatalogImageUrlResolver {

    private final ObjectStorageService storage;
    private final Duration ttl;

    public DefaultCatalogImageUrlResolver(
            ObjectStorageService storage,
            @Value("${app.catalog.image-url-ttl-seconds:900}") long ttlSeconds) {
        this.storage = storage;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public String resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        if (objectKey.startsWith("http://") || objectKey.startsWith("https://")) {
            return objectKey;
        }
        try {
            URI url = storage.signedGetUrl(objectKey, ttl);
            return url != null ? url.toString() : null;
        } catch (StorageException ex) {
            // Key is not resolvable under the storage policy — expose no URL.
            return null;
        }
    }
}
