package com.smartcoffee.infrastructure.storage;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Backend-authoritative object-key policy. The frontend NEVER supplies a whole
 * object key or a bucket name; it supplies a category (which maps to an allowed
 * prefix) and an original filename, and this component produces a safe key.
 *
 * <p>Guarantees enforced here:
 * <ul>
 *   <li>keys must fall under one of the configured allowed prefixes
 *       (arbitrary prefix / bucket selection is impossible),</li>
 *   <li>path traversal ({@code ..}), absolute paths, backslashes and null
 *       bytes are rejected,</li>
 *   <li>user filenames are sanitized to a conservative character set before
 *       being combined with a server-generated id.</li>
 * </ul>
 */
@Component
public class StoragePathPolicy {

    private static final int MAX_FILENAME_LENGTH = 128;

    private final StorageProperties properties;

    public StoragePathPolicy(StorageProperties properties) {
        this.properties = properties;
    }

    /** @return the immutable list of allowed top-level prefixes (e.g. {@code products/}). */
    public List<String> allowedPrefixes() {
        return properties.allowedPrefixes();
    }

    /**
     * Validates an already-composed object key against the policy. Used on every
     * read/write so no caller can escape the sandboxed prefixes.
     *
     * @throws StorageException if the key is blank, unsafe, or outside an
     *                          allowed prefix.
     */
    public void requireAllowedKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw StorageException.invalidObjectKey("Object key is required");
        }
        if (objectKey.indexOf('\0') >= 0) {
            throw StorageException.invalidObjectKey("Object key contains a null byte");
        }
        if (objectKey.contains("\\")) {
            throw StorageException.invalidObjectKey("Object key must not contain backslashes");
        }
        if (objectKey.startsWith("/")) {
            throw StorageException.invalidObjectKey("Object key must not be absolute");
        }
        for (String segment : objectKey.split("/")) {
            if (segment.equals("..")) {
                throw StorageException.invalidObjectKey("Object key must not contain path traversal");
            }
        }
        boolean allowed = properties.allowedPrefixes().stream().anyMatch(objectKey::startsWith);
        if (!allowed) {
            throw StorageException.invalidObjectKey(
                    "Object key must start with one of the allowed prefixes");
        }
    }

    /**
     * Composes a safe key of the form {@code {prefix}{id}-{safeFilename}} from a
     * caller-supplied prefix category and an untrusted original filename.
     *
     * @param prefix          must be one of the allowed prefixes (validated).
     * @param generatedId     server-generated unique id (e.g. a UUID).
     * @param originalFilename raw client filename; only its basename is used.
     * @return the sanitized object key, already validated against the policy.
     */
    public String buildObjectKey(String prefix, String generatedId, String originalFilename) {
        if (prefix == null || !properties.allowedPrefixes().contains(prefix)) {
            throw StorageException.invalidObjectKey(
                    "Unknown storage category: " + prefix);
        }
        if (generatedId == null || generatedId.isBlank()) {
            throw StorageException.invalidObjectKey("Generated id is required");
        }
        String safeName = sanitizeFilename(originalFilename);
        String key = prefix + generatedId + (safeName.isEmpty() ? "" : "-" + safeName);
        requireAllowedKey(key);
        return key;
    }

    /**
     * Reduces an untrusted filename to a conservative basename: strips any
     * directory components, drops null bytes and every character outside
     * {@code [a-zA-Z0-9._-]}, collapses runs of {@code -}/_{@code .}, trims
     * leading/trailing separators and caps the length.
     */
    public String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replace("\0", "");
        base = base.replaceAll("[^a-zA-Z0-9._-]", "-");
        base = base.replaceAll("[-_.]{2,}", "-");
        base = base.replaceAll("^[-_.]+|[-_.]+$", "");
        if (base.length() > MAX_FILENAME_LENGTH) {
            base = base.substring(0, MAX_FILENAME_LENGTH);
        }
        return base;
    }
}
