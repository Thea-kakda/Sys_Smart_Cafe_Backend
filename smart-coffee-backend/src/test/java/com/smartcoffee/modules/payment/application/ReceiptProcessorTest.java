package com.smartcoffee.modules.payment.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartcoffee.infrastructure.storage.ObjectMetadata;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.modules.payment.domain.Receipt;
import com.smartcoffee.modules.payment.domain.ReceiptStatus;
import com.smartcoffee.modules.payment.persistence.ReceiptRepository;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Processor units (corrective receipt slice §4/§5/§12): issuance uses only the
 * persisted snapshot, is idempotent on ISSUED, derives one deterministic object
 * key across retries, and records durable customer-safe failures.
 */
class ReceiptProcessorTest {

    private static final String SNAPSHOT = "{\"orderNumber\":\"O-9\",\"paymentReference\":\"PAY-9\","
            + "\"total\":\"11.00\",\"currency\":\"USD\","
            + "\"promotion\":{\"code\":\"SAVE10\",\"discountAmount\":\"1.00\"},"
            + "\"items\":[{\"product\":\"Latte\",\"variant\":\"Large\","
            + "\"customizations\":[\"Milk: Oat\"],\"quantity\":1}]}";

    private final ReceiptRepository receipts = mock(ReceiptRepository.class);
    private final ReceiptDocumentRenderer renderer = mock(ReceiptDocumentRenderer.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ReceiptProcessor processor = new ReceiptProcessor(receipts, renderer, storage);

    private Receipt receipt;

    @BeforeEach
    void setUp() {
        receipt = Receipt.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SNAPSHOT);
        when(receipts.lock(receipt.id())).thenReturn(Optional.of(receipt));
    }

    @Test
    void issuesFromPersistedSnapshotAndCapturesMetadata() {
        byte[] bytes = "document".getBytes();
        when(renderer.render(SNAPSHOT)).thenReturn(bytes);
        when(storage.upload(eq("receipts/" + receipt.id() + ".txt"), eq("text/plain"), anyLong(), any(InputStream.class)))
                .thenReturn(new ObjectMetadata("bucket", "receipts/" + receipt.id() + ".txt", "text/plain",
                        bytes.length, "etag-9", Instant.now()));

        ReceiptProcessor.Outcome outcome = processor.process(receipt.id());

        assertEquals(ReceiptProcessor.Outcome.ISSUED, outcome);
        assertEquals(ReceiptStatus.ISSUED, receipt.status());
        assertEquals("receipts/" + receipt.id() + ".txt", receipt.objectKey());
        assertEquals("text/plain", receipt.mimeType());
        assertEquals(bytes.length, receipt.sizeBytes());
        assertEquals("etag-9", receipt.checksum());
        assertEquals(1, receipt.attempts());
        assertNotNull(receipt.issuedAt());
        assertEquals(SNAPSHOT, receipt.snapshot(), "snapshot is rendered verbatim, never rebuilt");
        verify(renderer).render(SNAPSHOT);
    }

    @Test
    void issuedReplayPerformsNoStorageWork() {
        byte[] bytes = "document".getBytes();
        when(renderer.render(SNAPSHOT)).thenReturn(bytes);
        when(storage.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
                .thenReturn(new ObjectMetadata("bucket", "k", "text/plain", bytes.length, "etag", Instant.now()));
        processor.process(receipt.id());
        Instant issuedAt = receipt.issuedAt();

        ReceiptProcessor.Outcome replay = processor.process(receipt.id());

        assertEquals(ReceiptProcessor.Outcome.ALREADY_ISSUED, replay);
        assertEquals(issuedAt, receipt.issuedAt());
        assertEquals(1, receipt.attempts());
        verify(storage, times(1)).upload(anyString(), anyString(), anyLong(), any(InputStream.class));
        verify(renderer, times(1)).render(anyString());
    }

    @Test
    void storageFailureRecordsSafeCodeAndKeepsSnapshot() {
        when(renderer.render(SNAPSHOT)).thenReturn("document".getBytes());
        when(storage.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
                .thenThrow(new IllegalStateException("connect failed for /tmp/minio/secret-access-key"));

        ReceiptProcessor.Outcome outcome = processor.process(receipt.id());

        assertEquals(ReceiptProcessor.Outcome.FAILED, outcome);
        assertEquals(ReceiptStatus.FAILED, receipt.status());
        assertEquals(ReceiptProcessor.FAILURE_STORAGE, receipt.failureReason());
        assertFalse(receipt.failureReason().contains("/tmp"));
        assertFalse(receipt.failureReason().contains("secret"));
        assertNull(receipt.objectKey());
        assertEquals(SNAPSHOT, receipt.snapshot());
        assertEquals(1, receipt.attempts());
    }

    @Test
    void renderingFailureRecordsSafeCodeWithoutTouchingStorage() {
        when(renderer.render(SNAPSHOT)).thenThrow(new IllegalStateException("bad json at /var/secret"));

        ReceiptProcessor.Outcome outcome = processor.process(receipt.id());

        assertEquals(ReceiptProcessor.Outcome.FAILED, outcome);
        assertEquals(ReceiptProcessor.FAILURE_RENDERING, receipt.failureReason());
        assertEquals(ReceiptStatus.FAILED, receipt.status());
        verify(storage, never()).upload(anyString(), anyString(), anyLong(), any(InputStream.class));
    }

    @Test
    void retryAfterFailureReusesOneDeterministicObjectKey() {
        when(renderer.render(SNAPSHOT)).thenReturn("document".getBytes());
        when(storage.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
                .thenThrow(new IllegalStateException("transient"))
                .thenReturn(new ObjectMetadata("bucket", "k", "text/plain", 8, "etag", Instant.now()));

        assertEquals(ReceiptProcessor.Outcome.FAILED, processor.process(receipt.id()));
        assertEquals(ReceiptStatus.FAILED, receipt.status());

        assertEquals(ReceiptProcessor.Outcome.ISSUED, processor.process(receipt.id()));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(storage, times(2)).upload(keys.capture(), anyString(), anyLong(), any(InputStream.class));
        assertEquals(2, keys.getAllValues().size());
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1),
                "a retry overwrites the same object, never creates a second one");
        assertEquals(2, receipt.attempts());
        assertNull(receipt.failureReason());
    }

    @Test
    void unknownReceiptIsReportedWithoutWork() {
        UUID unknown = UUID.randomUUID();
        when(receipts.lock(unknown)).thenReturn(Optional.empty());

        assertEquals(ReceiptProcessor.Outcome.MISSING, processor.process(unknown));
        verifyNoInteractions(renderer, storage);
    }
}
