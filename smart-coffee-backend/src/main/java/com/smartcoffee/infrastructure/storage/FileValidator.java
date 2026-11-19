package com.smartcoffee.infrastructure.storage;

import org.springframework.stereotype.Component;

/**
 * Validates an inbound object BEFORE it is written to MinIO, using the
 * configurable {@link StorageProperties} allowlist. This is the single place
 * that decides whether a content type / size is acceptable, so the rules stay
 * uniform across receipts, catalog images, avatars and documents.
 *
 * <p>Executables and anything outside the allowlist are rejected by omission:
 * only explicitly listed content types pass.
 */
@Component
public class FileValidator {

    private final StorageProperties properties;

    public FileValidator(StorageProperties properties) {
        this.properties = properties;
    }

    /**
     * @param contentType declared MIME type (must be non-blank and allowlisted).
     * @param size        byte size of the payload.
     * @throws StorageException when the file is empty, oversize, or its type is
     *                          not permitted.
     */
    public void validate(String contentType, long size) {
        if (contentType == null || contentType.isBlank()) {
            throw StorageException.invalidFile("Content type is required");
        }
        if (size <= 0) {
            throw StorageException.invalidFile("File is empty");
        }
        if (size > properties.maxFileSizeBytes()) {
            throw StorageException.fileTooLarge(size, properties.maxFileSizeBytes());
        }
        String normalized = contentType.split(";")[0].trim().toLowerCase();
        boolean allowed = properties.allowedContentTypes().stream()
                .anyMatch(allowedType -> allowedType.equalsIgnoreCase(normalized));
        if (!allowed) {
            throw StorageException.unsupportedContentType(normalized);
        }
    }
}
