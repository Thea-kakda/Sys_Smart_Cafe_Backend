package com.smartcoffee.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the object-key policy — no MinIO, no Docker. These lock
 * down the path-security guarantees (traversal / absolute / null byte / prefix
 * enforcement / filename sanitization) that the storage service relies on.
 */
class StoragePathPolicyTest {

    private static final List<String> PREFIXES = List.of(
            "products/", "categories/", "users/", "invoices/", "receipts/", "documents/");

    private StoragePathPolicy policy() {
        return new StoragePathPolicy(new StorageProperties(PREFIXES, List.of("image/jpeg"), 1000, 900));
    }

    @Test
    void acceptsKeyUnderAllowedPrefix() {
        policy().requireAllowedKey("products/abc-latte.jpg");
    }

    @Test
    void rejectsPathTraversal() {
        assertThrows(StorageException.class, () -> policy().requireAllowedKey("products/../../etc/passwd"));
    }

    @Test
    void rejectsAbsoluteKey() {
        assertThrows(StorageException.class, () -> policy().requireAllowedKey("/products/x.jpg"));
    }

    @Test
    void rejectsBackslash() {
        assertThrows(StorageException.class, () -> policy().requireAllowedKey("products\\x.jpg"));
    }

    @Test
    void rejectsNullByte() {
        assertThrows(StorageException.class, () -> policy().requireAllowedKey("products/a\0b.jpg"));
    }

    @Test
    void rejectsKeyOutsideAllowedPrefixes() {
        assertThrows(StorageException.class, () -> policy().requireAllowedKey("secrets/x.txt"));
    }

    @Test
    void rejectsBlankKey() {
        assertThrows(StorageException.class, () -> policy().requireAllowedKey("  "));
    }

    @Test
    void buildsSafeKeyWithGeneratedIdAndSanitizedName() {
        String id = UUID.randomUUID().toString();
        String key = policy().buildObjectKey("products/", id, "  my coffee/../latte.jpg ");
        assertTrue(key.startsWith("products/" + id + "-"), key);
        // traversal and spaces must be neutralised in the filename component
        assertTrue(key.endsWith("latte.jpg"), key);
        assertTrue(!key.contains("..") && !key.contains(" "), key);
    }

    @Test
    void buildObjectKeyRejectsUnknownCategory() {
        assertThrows(StorageException.class,
                () -> policy().buildObjectKey("secrets/", UUID.randomUUID().toString(), "a.txt"));
    }

    @Test
    void sanitizeStripsDirectoryAndUnsafeChars() {
        StoragePathPolicy p = policy();
        String safe = p.sanitizeFilename("C:\\Users\\me\\../../bad name@#.png");
        assertEquals("bad-name-png".replace("png", "png"), safe.toLowerCase(), safe);
        assertTrue(!safe.contains("..") && !safe.contains("/") && !safe.contains("\\"), safe);
    }
}
