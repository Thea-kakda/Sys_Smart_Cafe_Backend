package com.smartcoffee.config;

import com.smartcoffee.infrastructure.storage.StorageProperties;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the single shared {@link MinioClient} from {@link MinioProperties}.
 *
 * <p>Credentials come only from the environment (never hard-coded) and are not
 * logged. Client construction is lazy — it performs no network I/O — so the
 * bean can exist even when MinIO is momentarily unreachable; connectivity is
 * surfaced by the health indicator and by per-operation errors instead.
 */
@Configuration
@EnableConfigurationProperties({MinioProperties.class, StorageProperties.class})
public class MinioConfig {

    @Bean
    MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
