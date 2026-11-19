package com.smartcoffee.modules.file.application;

import com.smartcoffee.common.exception.ValidationException;

/**
 * The categories a client may upload to / address through the file API, each
 * bound to exactly one backend-controlled object-key prefix.
 *
 * <p>This is deliberately a closed allowlist, not a free-form prefix. The client
 * picks a {@code category} segment; the server maps it to a fixed prefix and
 * builds the rest of the key itself. Users/invoices/receipts prefixes exist in
 * storage for provisioning and internal generation but are intentionally NOT
 * uploadable here (no profile write for avatars; receipts/invoices are produced
 * by their own domain services).
 */
public enum FileCategory {

    PRODUCTS("products", "products/"),
    CATEGORIES("categories", "categories/"),
    DOCUMENTS("documents", "documents/");

    private final String segment;
    private final String prefix;

    FileCategory(String segment, String prefix) {
        this.segment = segment;
        this.prefix = prefix;
    }

    public String segment() {
        return segment;
    }

    public String prefix() {
        return prefix;
    }

    /** Resolves a path segment to a category, rejecting anything not allowlisted. */
    public static FileCategory fromSegment(String segment) {
        if (segment != null) {
            for (FileCategory c : values()) {
                if (c.segment.equalsIgnoreCase(segment)) {
                    return c;
                }
            }
        }
        throw new ValidationException("Unknown file category: " + segment);
    }

    /**
     * Verifies an existing object key belongs to THIS category's prefix. Used on
     * signed-URL and delete requests so a caller can never address another
     * category's objects through the wrong endpoint.
     */
    public void requireOwnKey(String objectKey) {
        if (objectKey == null || !objectKey.startsWith(prefix)) {
            throw com.smartcoffee.infrastructure.storage.StorageException.invalidObjectKey(
                    "Object key does not belong to category '" + segment + "'");
        }
    }
}
