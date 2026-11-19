package com.smartcoffee.modules.catalog.application;

/**
 * Resolves a stored catalog image object key into a URL the frontend can load.
 *
 * <p>Images live in the private MinIO bucket, so resolution yields a short-lived
 * presigned GET URL rather than a permanent public URL. The database stores the
 * object key only (e.g. {@code products/…-latte.jpg}), never a URL.
 */
public interface CatalogImageUrlResolver {
    String resolve(String objectKey);
}
