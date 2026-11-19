package com.smartcoffee.modules.payment.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Receipt lifecycle units (corrective receipt slice §3/§12): PENDING →
 * PROCESSING → ISSUED, FAILED → PROCESSING retry, ISSUED terminal, and a
 * bounded safe failure reason. The financial snapshot is immutable throughout.
 */
class ReceiptLifecycleTest {

    private static final String SNAPSHOT = "{\"orderNumber\":\"O-1\",\"total\":\"10.00\","
            + "\"promotion\":{\"code\":\"SAVE10\"}}";

    private Receipt pending() {
        return Receipt.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SNAPSHOT);
    }

    @Test
    void pendingToProcessingClaimsOneAttempt() {
        Receipt r = pending();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");

        r.beginAttempt(now);

        assertEquals(ReceiptStatus.PROCESSING, r.status());
        assertEquals(1, r.attempts());
        assertEquals(now, r.lastAttemptAt());
        assertNull(r.failureReason());
        assertEquals(SNAPSHOT, r.snapshot());
    }

    @Test
    void processingToIssuedCapturesStorageMetadata() {
        Receipt r = pending();
        r.beginAttempt(Instant.now());
        Instant before = Instant.now();

        r.issued("receipts/" + r.id() + ".txt", "text/plain", 42L, "etag-1");

        assertEquals(ReceiptStatus.ISSUED, r.status());
        assertEquals("text/plain", r.mimeType());
        assertEquals(42L, r.sizeBytes());
        assertEquals("etag-1", r.checksum());
        assertNotNull(r.issuedAt());
        assertFalse(r.issuedAt().isBefore(before));
        assertEquals("receipts/" + r.id() + ".txt", r.objectKey());
        assertEquals(SNAPSHOT, r.snapshot());
    }

    @Test
    void failureMarksFailedAndRetryResumesProcessing() {
        Receipt r = pending();
        r.beginAttempt(Instant.now());
        r.failed(ReceiptProcessorCodes.STORAGE);

        assertEquals(ReceiptStatus.FAILED, r.status());
        assertEquals(ReceiptProcessorCodes.STORAGE, r.failureReason());

        Instant retry = Instant.now().plusSeconds(60);
        r.beginAttempt(retry);

        assertEquals(ReceiptStatus.PROCESSING, r.status());
        assertEquals(2, r.attempts());
        assertEquals(retry, r.lastAttemptAt());
        assertNull(r.failureReason(), "a retry clears the stale diagnostic");
        assertEquals(SNAPSHOT, r.snapshot());
    }

    @Test
    void issuedReplayWithSameObjectIsNoOp() {
        Receipt r = pending();
        r.beginAttempt(Instant.now());
        r.issued("receipts/k.txt", "text/plain", 10L, "etag");
        Instant issuedAt = r.issuedAt();
        long version = r.version();

        r.issued("receipts/k.txt", "text/plain", 10L, "etag");

        assertEquals(ReceiptStatus.ISSUED, r.status());
        assertEquals(issuedAt, r.issuedAt());
        assertEquals(version, r.version());
        assertEquals(1, r.attempts());
    }

    @Test
    void issuedIsTerminalForReprocessingAndFailure() {
        Receipt r = pending();
        r.beginAttempt(Instant.now());
        r.issued("receipts/k.txt", "text/plain", 10L, "etag");

        assertThrows(ConflictException.class, () -> r.beginAttempt(Instant.now()));
        assertThrows(ConflictException.class, () -> r.failed("RECEIPT_STORAGE_FAILED"));
        assertThrows(ConflictException.class,
                () -> r.issued("receipts/other.txt", "text/plain", 10L, "etag"));
        assertEquals(ReceiptStatus.ISSUED, r.status());
    }

    @Test
    void failureReasonIsBoundedAndCustomerSafe() {
        Receipt r = pending();
        r.beginAttempt(Instant.now());
        r.failed("X".repeat(900));

        assertEquals(500, r.failureReason().length());
    }

    @Test
    void financialAndPromotionSnapshotsAreNeverMutated() {
        Receipt r = pending();
        r.beginAttempt(Instant.now());
        r.failed(ReceiptProcessorCodes.STORAGE);
        r.beginAttempt(Instant.now());
        r.issued("receipts/k.txt", "text/plain", 10L, "etag");

        assertEquals(SNAPSHOT, r.snapshot());
        assertTrue(r.snapshot().contains("\"promotion\""), "promotion snapshot preserved");
        assertTrue(r.snapshot().contains("\"total\""), "financial totals preserved");
    }

    /** Shared with the processor; kept here to avoid importing production strings into assertions. */
    private static final class ReceiptProcessorCodes {
        static final String STORAGE = "RECEIPT_STORAGE_FAILED";
    }
}
