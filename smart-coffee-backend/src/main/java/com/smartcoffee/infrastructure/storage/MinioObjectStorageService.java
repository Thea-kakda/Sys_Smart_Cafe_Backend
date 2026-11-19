package com.smartcoffee.infrastructure.storage;

import com.smartcoffee.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * MinIO-backed {@link ObjectStorageService}. This is the single storage
 * implementation: it targets the one configured private bucket, enforces the
 * backend's object-key policy on every operation, validates files before
 * upload, and translates MinIO errors into clean {@link StorageException}s.
 *
 * <p>Bucket credentials never leave the server; only short-lived presigned URLs
 * are handed to callers that need direct, time-boxed read access.
 */
@Service
public class MinioObjectStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorageService.class);

    private static final String MARKER_NAME = ".keep";
    private static final String MARKER_CONTENT_TYPE = "application/octet-stream";

    /** Presigned URL expiry bounds accepted by the S3/MinIO signature spec. */
    private static final long MIN_SIGNED_URL_SECONDS = 1L;
    private static final long MAX_SIGNED_URL_SECONDS = 604_800L; // 7 days

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final StorageProperties storageProperties;
    private final StoragePathPolicy pathPolicy;
    private final FileValidator fileValidator;

    public MinioObjectStorageService(
            MinioClient minioClient,
            MinioProperties minioProperties,
            StorageProperties storageProperties,
            StoragePathPolicy pathPolicy,
            FileValidator fileValidator) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.storageProperties = storageProperties;
        this.pathPolicy = pathPolicy;
        this.fileValidator = fileValidator;
    }

    private String bucket() {
        String bucket = minioProperties.bucket();
        if (bucket == null || bucket.isBlank()) {
            throw StorageException.bucketUnavailable(
                    new IllegalStateException("app.minio.bucket is not configured"));
        }
        return bucket;
    }

    @Override
    public void ensureBucket() {
        String bucket = bucket();
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (exists) {
                log.info("MinIO bucket verified: {}", bucket);
                return;
            }
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("MinIO bucket created: {}", bucket);
        } catch (ErrorResponseException ex) {
            // Another node may have created it concurrently; that is not a failure.
            if (isBucketAlreadyPresent(ex)) {
                log.info("MinIO bucket already present: {}", bucket);
                return;
            }
            throw StorageException.bucketUnavailable(ex);
        } catch (Exception ex) {
            throw StorageException.bucketUnavailable(ex);
        }
    }

    @Override
    public void ensureBasePaths() {
        String bucket = bucket();
        for (String prefix : pathPolicy.allowedPrefixes()) {
            String markerKey = prefix + MARKER_NAME;
            try {
                if (statOrNull(bucket, markerKey) != null) {
                    continue; // marker already present — leave it untouched
                }
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(markerKey)
                        .stream(new ByteArrayInputStream(new byte[0]), 0, -1)
                        .contentType(MARKER_CONTENT_TYPE)
                        .build());
                log.info("MinIO base path verified: {}{}", bucket, markerKey);
            } catch (Exception ex) {
                throw StorageException.storageUnavailable(
                        "Could not provision base path " + prefix, ex);
            }
        }
    }

    @Override
    public ObjectMetadata upload(
            String objectKey, String contentType, long size, InputStream data) {
        String bucket = bucket();
        pathPolicy.requireAllowedKey(objectKey);
        fileValidator.validate(contentType, size);
        if (data == null) {
            throw StorageException.invalidFile("Object input stream is required");
        }
        try {
            var response = minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(data, size, -1)
                    .contentType(contentType)
                    .build());
            log.info("Object uploaded: {}/{} ({} bytes)", bucket, objectKey, size);
            return new ObjectMetadata(
                    bucket, objectKey, contentType, size, response.etag(), Instant.now());
        } catch (Exception ex) {
            throw StorageException.uploadFailed(objectKey, ex);
        }
    }

    @Override
    public byte[] download(String objectKey) {
        String bucket = bucket();
        pathPolicy.requireAllowedKey(objectKey);
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException ex) {
            if (isNotFound(ex)) {
                throw StorageException.objectNotFound(objectKey);
            }
            throw StorageException.downloadFailed(objectKey, ex);
        } catch (Exception ex) {
            throw StorageException.downloadFailed(objectKey, ex);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        String bucket = bucket();
        pathPolicy.requireAllowedKey(objectKey);
        return statOrNull(bucket, objectKey) != null;
    }

    @Override
    public ObjectMetadata getObjectMetadata(String objectKey) {
        String bucket = bucket();
        pathPolicy.requireAllowedKey(objectKey);
        StatObjectResponse stat = statOrThrow(bucket, objectKey);
        Instant created = stat.lastModified() != null
                ? stat.lastModified().toInstant() : Instant.now();
        return new ObjectMetadata(
                bucket, objectKey, stat.contentType(), stat.size(), stat.etag(), created);
    }

    @Override
    public URI signedGetUrl(String objectKey, Duration ttl) {
        String bucket = bucket();
        pathPolicy.requireAllowedKey(objectKey);
        long seconds = ttl != null ? ttl.toSeconds() : storageProperties.signedUrlTtlSeconds();
        if (seconds < MIN_SIGNED_URL_SECONDS) {
            seconds = MIN_SIGNED_URL_SECONDS;
        }
        if (seconds > MAX_SIGNED_URL_SECONDS) {
            seconds = MAX_SIGNED_URL_SECONDS;
        }
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) seconds)
                    .build());
            return URI.create(url);
        } catch (Exception ex) {
            throw StorageException.storageUnavailable(
                    "Could not sign download URL for " + objectKey, ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        String bucket = bucket();
        pathPolicy.requireAllowedKey(objectKey);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            log.info("Object deleted: {}/{}", bucket, objectKey);
        } catch (Exception ex) {
            throw StorageException.deleteFailed(objectKey, ex);
        }
    }

    // ---- helpers ----------------------------------------------------------

    private StatObjectResponse statOrNull(String bucket, String objectKey) {
        try {
            return minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException ex) {
            if (isNotFound(ex)) {
                return null;
            }
            throw StorageException.storageUnavailable(
                    "Could not stat object " + objectKey, ex);
        } catch (Exception ex) {
            throw StorageException.storageUnavailable(
                    "Could not stat object " + objectKey, ex);
        }
    }

    private StatObjectResponse statOrThrow(String bucket, String objectKey) {
        StatObjectResponse stat = statOrNull(bucket, objectKey);
        if (stat == null) {
            throw StorageException.objectNotFound(objectKey);
        }
        return stat;
    }

    private static boolean isNotFound(ErrorResponseException ex) {
        String code = ex.errorResponse() != null ? ex.errorResponse().code() : "";
        return "NoSuchKey".equals(code) || "XMinioNoSuchKey".equals(code)
                || "NoSuchObject".equals(code);
    }

    private static boolean isBucketAlreadyPresent(ErrorResponseException ex) {
        String code = ex.errorResponse() != null ? ex.errorResponse().code() : "";
        return "BucketAlreadyOwnedByYou".equals(code) || "BucketAlreadyExists".equals(code);
    }
}
