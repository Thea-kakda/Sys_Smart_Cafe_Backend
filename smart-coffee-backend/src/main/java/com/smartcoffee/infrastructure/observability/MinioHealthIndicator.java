package com.smartcoffee.infrastructure.observability;

import com.smartcoffee.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for MinIO object storage, following the project's
 * existing {@link HealthIndicator} convention (see the DB readiness indicator).
 *
 * <p>Performs a single lightweight {@code bucketExists} probe — only when the
 * health endpoint is hit, never on ordinary business requests. Reports the
 * bucket name and reachability; credentials and raw errors are never exposed.
 */
@Component("minio")
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioHealthIndicator(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public Health health() {
        String bucket = properties.bucket();
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            return Health.up()
                    .withDetail("bucket", bucket)
                    .withDetail("provisioned", exists)
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("bucket", bucket)
                    .withDetail("reason", "MinIO is unreachable")
                    .build();
        }
    }
}
