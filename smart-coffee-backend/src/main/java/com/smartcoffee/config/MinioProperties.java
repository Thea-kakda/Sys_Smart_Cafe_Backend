package com.smartcoffee.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO connection + bucket configuration, bound from {@code app.minio.*}
 * (application.yml maps these from the {@code MINIO_*} environment variables).
 *
 * <p>The application owns exactly ONE private bucket ({@code bucket}); callers
 * never select a bucket, they address objects by key under an allowed prefix.
 * Credentials are supplied externally and are never hard-coded or logged.
 */
@ConfigurationProperties(prefix = "app.minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket) {
}
