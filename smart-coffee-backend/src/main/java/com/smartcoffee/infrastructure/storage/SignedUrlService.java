package com.smartcoffee.infrastructure.storage;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;

/**
 * Thin convenience wrapper for issuing short-lived private download URLs. The
 * bucket is resolved by the storage service from configuration; callers supply
 * only the object key and (optionally) a TTL.
 */
@Service
public class SignedUrlService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final ObjectStorageService storage;

    public SignedUrlService(ObjectStorageService storage) {
        this.storage = storage;
    }

    public URI privateDownload(String objectKey) {
        return privateDownload(objectKey, DEFAULT_TTL);
    }

    public URI privateDownload(String objectKey, Duration ttl) {
        return storage.signedGetUrl(objectKey, ttl);
    }
}
