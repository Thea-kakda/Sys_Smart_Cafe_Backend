package com.smartcoffee.modules.file.application;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.infrastructure.storage.ObjectMetadata;
import com.smartcoffee.infrastructure.storage.StoragePathPolicy;
import com.smartcoffee.infrastructure.storage.StorageProperties;
import com.smartcoffee.modules.file.api.FileSignedUrlResponse;
import com.smartcoffee.modules.file.api.FileUploadResponse;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Application service for the file API. It layers authorization and per-category
 * prefix binding on top of the existing {@link ObjectStorageService} — the
 * storage abstraction is NOT reimplemented here.
 *
 * <p>Responsibilities:
 * <ul>
 *   <enforce>require the right permission (service-layer authz, per this project's
 *       convention);</enforce>
 *   <li>map a client {@code category} to a fixed server-side prefix;</li>
 *   <li>generate the object key server-side ({prefix}{uuid}-{safeFilename}) so a
 *       client can never choose a bucket, an arbitrary prefix or an
 *       unrestricted key;</li>
 *   <li>bind later reads/deletes to the SAME category prefix so one endpoint
 *       cannot reach another category's objects.</li>
 * </ul>
 * File validation (content type, size, traversal) is enforced centrally by the
 * storage layer ({@link StoragePathPolicy} + {@code FileValidator}), never
 * duplicated here.
 */
@Service
public class FileStorageService {

    private final ObjectStorageService storage;
    private final StoragePathPolicy pathPolicy;
    private final PermissionEvaluator permissions;
    private final StorageProperties storageProperties;

    public FileStorageService(ObjectStorageService storage, StoragePathPolicy pathPolicy,
            PermissionEvaluator permissions, StorageProperties storageProperties) {
        this.storage = storage;
        this.pathPolicy = pathPolicy;
        this.permissions = permissions;
        this.storageProperties = storageProperties;
    }

    public FileUploadResponse upload(CurrentActor actor, String categorySegment,
            String originalFilename, String contentType, long size, InputStream data) {
        permissions.require(actor, PermissionCodes.FILE_UPLOAD);
        FileCategory category = FileCategory.fromSegment(categorySegment);

        // Server-owned key: fixed prefix + fresh id + sanitized filename. The
        // original filename is NEVER trusted as the unique key.
        String objectKey = pathPolicy.buildObjectKey(
                category.prefix(), UUID.randomUUID().toString(), originalFilename);

        // storage.upload enforces content-type/size and the prefix policy.
        ObjectMetadata meta = storage.upload(objectKey, contentType, size, data);

        URI url = storage.signedGetUrl(objectKey, signedUrlTtl());
        return new FileUploadResponse(objectKey, originalFilename, meta.contentType(),
                meta.sizeBytes(), url.toString());
    }

    public FileSignedUrlResponse signedUrl(CurrentActor actor, String categorySegment, String objectKey) {
        permissions.require(actor, PermissionCodes.FILE_UPLOAD);
        FileCategory category = FileCategory.fromSegment(categorySegment);
        category.requireOwnKey(objectKey);

        Duration ttl = signedUrlTtl();
        URI url = storage.signedGetUrl(objectKey, ttl);
        return new FileSignedUrlResponse(objectKey, url.toString(), ttl.toSeconds());
    }

    public void delete(CurrentActor actor, String categorySegment, String objectKey) {
        permissions.require(actor, PermissionCodes.FILE_DELETE);
        FileCategory category = FileCategory.fromSegment(categorySegment);
        category.requireOwnKey(objectKey);
        storage.delete(objectKey);
    }

    private Duration signedUrlTtl() {
        return Duration.ofSeconds(storageProperties.signedUrlTtlSeconds());
    }
}
