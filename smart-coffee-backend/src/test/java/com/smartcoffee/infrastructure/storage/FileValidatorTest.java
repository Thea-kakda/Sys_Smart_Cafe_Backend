package com.smartcoffee.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the upload validation gate: content-type allowlist, empty-file
 * and oversize rejection. Executables are rejected simply because they are not
 * on the allowlist.
 */
class FileValidatorTest {

    private static final long MAX = 1024L;

    private FileValidator validator() {
        return new FileValidator(new StorageProperties(
                List.of("products/"),
                List.of("image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain"),
                MAX, 900));
    }

    @Test
    void acceptsAllowlistedImage() {
        validator().validate("image/jpeg", 10L);
    }

    @Test
    void acceptsCharsetParameter() {
        validator().validate("text/plain; charset=utf-8", 10L);
    }

    @Test
    void rejectsUnsupportedType() {
        StorageException ex = assertThrows(StorageException.class,
                () -> validator().validate("application/x-msdownload", 10L));
        assertEquals("UNSUPPORTED_CONTENT_TYPE", ex.code());
    }

    @Test
    void rejectsEmptyFile() {
        StorageException ex = assertThrows(StorageException.class,
                () -> validator().validate("image/png", 0L));
        assertEquals("INVALID_FILE", ex.code());
    }

    @Test
    void rejectsOversizeFile() {
        StorageException ex = assertThrows(StorageException.class,
                () -> validator().validate("image/png", MAX + 1));
        assertEquals("FILE_TOO_LARGE", ex.code());
    }

    @Test
    void rejectsMissingContentType() {
        StorageException ex = assertThrows(StorageException.class,
                () -> validator().validate("  ", 10L));
        assertEquals("INVALID_FILE", ex.code());
    }
}
