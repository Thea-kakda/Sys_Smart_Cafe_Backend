package com.smartcoffee.modules.payment.application;

import com.smartcoffee.infrastructure.storage.ObjectMetadata;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.modules.payment.domain.Receipt;
import com.smartcoffee.modules.payment.domain.ReceiptStatus;
import com.smartcoffee.modules.payment.persistence.ReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues one receipt document from the persisted snapshot (corrective receipt
 * slice). Idempotent by construction:
 * <ul>
 *   <li>the row is locked with {@code SELECT ... FOR UPDATE}, so concurrent
 *       consumers serialize and exactly one performs the work;</li>
 *   <li>ISSUED is terminal and a replay is a no-op;</li>
 *   <li>the object key is derived from the immutable receipt id, so a retry
 *       overwrites the same object instead of creating a duplicate;</li>
 *   <li>the snapshot string is rendered verbatim — no live price, product,
 *       variant or promotion data is ever reloaded.</li>
 * </ul>
 *
 * <p>Storage/rendering failures are recorded durably as FAILED with a bounded
 * safe code (never an exception message or storage path) so the recovery worker
 * can retry later; they are not thrown, so the broker message can be
 * acknowledged after the failure state is committed.</p>
 */
@Service
public class ReceiptProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReceiptProcessor.class);

    public static final String CONTENT_TYPE = "text/plain";
    public static final String FAILURE_STORAGE = "RECEIPT_STORAGE_FAILED";
    public static final String FAILURE_RENDERING = "RECEIPT_RENDERING_FAILED";

    public enum Outcome { ISSUED, ALREADY_ISSUED, FAILED, MISSING }

    private final ReceiptRepository receipts;
    private final ReceiptDocumentRenderer renderer;
    private final ObjectStorageService storage;

    public ReceiptProcessor(
            ReceiptRepository receipts,
            ReceiptDocumentRenderer renderer,
            ObjectStorageService storage
    ) {
        this.receipts = receipts;
        this.renderer = renderer;
        this.storage = storage;
    }

    @Transactional
    public Outcome process(UUID receiptId) {
        Receipt receipt = receipts.lock(receiptId).orElse(null);
        if (receipt == null) {
            log.warn("Receipt issuance requested for unknown receipt status=MISSING receiptId={}", receiptId);
            return Outcome.MISSING;
        }
        if (receipt.status() == ReceiptStatus.ISSUED) {
            return Outcome.ALREADY_ISSUED;
        }

        receipt.beginAttempt(Instant.now());

        byte[] bytes;
        try {
            bytes = renderer.render(receipt.snapshot());
        } catch (RuntimeException exception) {
            receipt.failed(FAILURE_RENDERING);
            log.warn("Receipt rendering failed receiptId={} error={}", receiptId,
                    exception.getClass().getSimpleName());
            return Outcome.FAILED;
        }

        // Stored in the shared private bucket under the 'receipts/' prefix. The
        // key is derived from the immutable receipt id: retries overwrite the
        // same object, never create a second one.
        String objectKey = "receipts/" + receiptId + ".txt";

        ObjectMetadata metadata;
        try (var data = new ByteArrayInputStream(bytes)) {
            metadata = storage.upload(objectKey, CONTENT_TYPE, bytes.length, data);
        } catch (Exception exception) {
            receipt.failed(FAILURE_STORAGE);
            // Class name only: exception messages may carry paths or
            // credentials and must never reach the durable receipt row.
            log.warn("Receipt storage failed receiptId={} error={}", receiptId,
                    exception.getClass().getSimpleName());
            return Outcome.FAILED;
        }

        receipt.issued(objectKey, CONTENT_TYPE, metadata.sizeBytes(), metadata.checksum());
        return Outcome.ISSUED;
    }
}
