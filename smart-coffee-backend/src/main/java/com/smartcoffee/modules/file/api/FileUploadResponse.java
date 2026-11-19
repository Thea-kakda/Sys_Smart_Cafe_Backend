package com.smartcoffee.modules.file.api;

/**
 * Result of a successful file upload. Carries only what the frontend needs: the
 * server-assigned {@code objectKey} (to persist on the owning entity, e.g. a
 * product's {@code image_object_key}), the original filename echo, the stored
 * content type/size, and a short-lived presigned {@code url} for immediate
 * preview. No bucket name and no MinIO credentials are ever exposed.
 */
public record FileUploadResponse(
        String objectKey,
        String originalFilename,
        String contentType,
        long size,
        String url) {
}
