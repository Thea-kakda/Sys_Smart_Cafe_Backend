package com.smartcoffee.modules.file.api;

/**
 * A freshly issued short-lived presigned GET URL for an existing object. The
 * {@code url} is temporary and safe to hand to the browser; it embeds no
 * long-lived credentials. {@code expiresIn} is the TTL (seconds) so clients can
 * refresh before the link lapses.
 */
public record FileSignedUrlResponse(
        String objectKey,
        String url,
        long expiresIn) {
}
