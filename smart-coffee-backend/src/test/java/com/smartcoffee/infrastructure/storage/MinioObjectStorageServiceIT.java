package com.smartcoffee.infrastructure.storage;

import com.smartcoffee.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end storage behaviour against the Testcontainers MinIO instance.
 * Verifies the single-bucket model, idempotent provisioning, the full
 * upload/download/exists/metadata/signed-URL/delete round trip, and the
 * security rejections (traversal, disallowed prefix, unsupported type).
 */
class MinioObjectStorageServiceIT extends IntegrationTestBase {

    private static final String BUCKET = "smart-coffee-storage";

    @Autowired
    private ObjectStorageService storage;

    @Test
    void provisioningIsIdempotentAndCreatesBasePrefixes() {
        assertDoesNotThrow(() -> {
            storage.ensureBucket();
            storage.ensureBucket(); // second run must not fail
            storage.ensureBasePaths();
            storage.ensureBasePaths();
        });
        // Marker objects make the prefixes visible in the console.
        assertTrue(storage.exists("products/.keep"));
        assertTrue(storage.exists("receipts/.keep"));
    }

    @Test
    void uploadDownloadMetadataSignedUrlAndDeleteRoundTrip() {
        String key = "products/" + UUID.randomUUID() + "-latte.png";
        byte[] payload = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);

        ObjectMetadata uploaded = storage.upload(
                key, "image/png", payload.length, new ByteArrayInputStream(payload));
        assertEquals(BUCKET, uploaded.bucket());
        assertEquals(key, uploaded.objectKey());
        assertEquals("image/png", uploaded.contentType());
        assertEquals(payload.length, uploaded.sizeBytes());

        assertTrue(storage.exists(key));

        assertArrayEquals(payload, storage.download(key));

        ObjectMetadata meta = storage.getObjectMetadata(key);
        assertEquals(payload.length, meta.sizeBytes());
        assertEquals("image/png", meta.contentType());

        URI signed = storage.signedGetUrl(key, Duration.ofMinutes(5));
        assertNotNull(signed);
        // A presigned URL carries signature query params and targets the bucket.
        assertTrue(signed.toString().contains(BUCKET), signed.toString());
        assertTrue(signed.toString().toLowerCase().contains("x-amz-"), signed.toString());

        storage.delete(key);
        assertFalse(storage.exists(key));
    }

    @Test
    void receiptKeyStoredUnderReceiptsPrefixInSharedBucket() {
        UUID receiptId = UUID.randomUUID();
        String key = "receipts/" + receiptId + ".txt";
        byte[] payload = "receipt snapshot".getBytes(StandardCharsets.UTF_8);

        ObjectMetadata meta = storage.upload(
                key, "text/plain", payload.length, new ByteArrayInputStream(payload));
        assertEquals(BUCKET, meta.bucket());
        assertEquals(key, meta.objectKey());
        assertArrayEquals(payload, storage.download(key));

        storage.delete(key);
    }

    @Test
    void rejectsPathTraversal() {
        StorageException ex = assertThrows(StorageException.class, () -> storage.upload(
                "products/../../etc/passwd", "image/png", 3,
                new ByteArrayInputStream(new byte[3])));
        assertEquals("INVALID_OBJECT_KEY", ex.code());
    }

    @Test
    void rejectsKeyOutsideAllowedPrefix() {
        StorageException ex = assertThrows(StorageException.class, () -> storage.upload(
                "secrets/x.txt", "text/plain", 3,
                new ByteArrayInputStream(new byte[3])));
        assertEquals("INVALID_OBJECT_KEY", ex.code());
    }

    @Test
    void rejectsUnsupportedContentType() {
        String key = "documents/" + UUID.randomUUID() + ".exe";
        StorageException ex = assertThrows(StorageException.class, () -> storage.upload(
                key, "application/x-msdownload", 3,
                new ByteArrayInputStream(new byte[3])));
        assertEquals("UNSUPPORTED_CONTENT_TYPE", ex.code());
    }
}
