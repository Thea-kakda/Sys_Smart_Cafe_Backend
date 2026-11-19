package com.smartcoffee.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Idempotent storage provisioning on application startup.
 *
 * <p>Ensures the single private bucket exists and that every required base
 * prefix ({@code products/}, {@code categories/}, …) is present via marker
 * objects. Safe to run repeatedly: an existing bucket is never recreated and no
 * existing object is ever deleted.
 *
 * <p>Provisioning failures are logged as warnings and do NOT abort startup — a
 * temporarily unreachable MinIO must not take the whole API down. Connectivity
 * is surfaced by the {@code minio} health indicator and by per-operation errors.
 */
@Component
public class MinioBucketProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MinioBucketProvisioner.class);

    private final ObjectStorageService storage;

    public MinioBucketProvisioner(ObjectStorageService storage) {
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            storage.ensureBucket();
            storage.ensureBasePaths();
            log.info("MinIO storage provisioning complete");
        } catch (Exception ex) {
            log.warn("MinIO provisioning skipped; storage may be unavailable: {}",
                    ex.getMessage());
        }
    }
}
