package com.smartcoffee.infrastructure.storage;

import com.smartcoffee.common.exception.BusinessException;

/**
 * Application-level storage error. Extends {@link BusinessException} so the
 * existing {@code GlobalExceptionHandler} already maps it to a clean
 * {@code ApiErrorResponse} with a stable code + HTTP status — raw MinIO stack
 * traces, credentials and internal endpoints never reach the client.
 */
public class StorageException extends BusinessException {

    public StorageException(String code, String message, int status) {
        super(code, message, status);
    }

    public StorageException(String code, String message, int status, Throwable cause) {
        super(code, message, status, cause);
    }

    /** The object key is malformed, outside an allowed prefix, or unsafe. */
    public static StorageException invalidObjectKey(String message) {
        return new StorageException("INVALID_OBJECT_KEY", message, 400);
    }

    /** Rejected before upload: bad content type, empty, or oversize file. */
    public static StorageException invalidFile(String message) {
        return new StorageException("INVALID_FILE", message, 400);
    }

    public static StorageException unsupportedContentType(String contentType) {
        return new StorageException("UNSUPPORTED_CONTENT_TYPE",
                "Content type is not allowed: " + contentType, 415);
    }

    public static StorageException fileTooLarge(long size, long max) {
        return new StorageException("FILE_TOO_LARGE",
                "File of " + size + " bytes exceeds the " + max + " byte limit", 413);
    }

    public static StorageException objectNotFound(String objectKey) {
        return new StorageException("OBJECT_NOT_FOUND",
                "Object not found: " + objectKey, 404);
    }

    public static StorageException uploadFailed(String objectKey, Throwable cause) {
        return new StorageException("UPLOAD_FAILED",
                "Could not store object: " + objectKey, 502, cause);
    }

    public static StorageException downloadFailed(String objectKey, Throwable cause) {
        return new StorageException("DOWNLOAD_FAILED",
                "Could not read object: " + objectKey, 502, cause);
    }

    public static StorageException deleteFailed(String objectKey, Throwable cause) {
        return new StorageException("DELETE_FAILED",
                "Could not delete object: " + objectKey, 502, cause);
    }

    public static StorageException bucketUnavailable(Throwable cause) {
        return new StorageException("BUCKET_UNAVAILABLE",
                "Storage bucket is unavailable", 503, cause);
    }

    public static StorageException storageUnavailable(String message, Throwable cause) {
        return new StorageException("STORAGE_UNAVAILABLE", message, 503, cause);
    }
}
