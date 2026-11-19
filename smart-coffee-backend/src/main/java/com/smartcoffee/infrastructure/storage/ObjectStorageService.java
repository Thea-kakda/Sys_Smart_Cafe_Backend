package com.smartcoffee.infrastructure.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * Application object-storage abstraction.
 *
 * <p>Deliberately <strong>bucket-free</strong>: callers address objects by key
 * only. The backing bucket is fixed by configuration ({@code app.minio.bucket})
 * and every key is validated against the backend's allowed-prefix policy, so no
 * caller can select an arbitrary bucket or escape its sandbox.
 */
public interface ObjectStorageService {

    /** Provisions the configured bucket if it does not already exist. Idempotent. */
    void ensureBucket();

    /** Ensures the required base prefixes exist (marker objects). Idempotent; never deletes. */
    void ensureBasePaths();

    ObjectMetadata upload(String objectKey, String contentType, long size, InputStream data);

    byte[] download(String objectKey);

    boolean exists(String objectKey);

    ObjectMetadata getObjectMetadata(String objectKey);

    URI signedGetUrl(String objectKey, Duration ttl);

    void delete(String objectKey);
}
