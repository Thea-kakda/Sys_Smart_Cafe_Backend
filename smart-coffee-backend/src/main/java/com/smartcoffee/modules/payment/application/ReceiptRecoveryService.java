package com.smartcoffee.modules.payment.application;

import com.smartcoffee.modules.payment.persistence.ReceiptRecoveryClaimRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Recovery worker for stranded receipts (corrective receipt slice, spec §8).
 *
 * <p>Claims a bounded batch of PENDING requests older than the safe threshold,
 * FAILED receipts below the attempt ceiling whose exponential backoff has
 * elapsed, and stale PROCESSING claims; then issues each one through the same
 * idempotent {@link ReceiptProcessor} used by the event consumer. Each receipt
 * runs in its own transaction — no transaction is held across the batch — and
 * the claim is multi-node safe ({@code FOR UPDATE SKIP LOCKED} + claim token),
 * so two nodes never issue the same document twice.
 */
@Service
public class ReceiptRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptRecoveryService.class);

    private static final String PROCESSING_ERROR = "RECEIPT_PROCESSING_ERROR";

    private final ReceiptRecoveryClaimRepository claims;
    private final ReceiptProcessor processor;
    private final Clock clock;

    private final int batchSize;
    private final int maxAttempts;
    private final Duration pendingMinAge;
    private final Duration staleClaim;
    /** Stable per-instance owner id, mirroring the other claim workers. */
    private final String owner = UUID.randomUUID().toString();

    public ReceiptRecoveryService(ReceiptRecoveryClaimRepository claims, ReceiptProcessor processor, Clock clock,
            @Value("${app.receipt.recovery-batch-size:50}") int batchSize,
            @Value("${app.receipt.recovery-max-attempts:5}") int maxAttempts,
            @Value("${app.receipt.recovery-pending-min-age:PT2M}") Duration pendingMinAge,
            @Value("${app.receipt.recovery-stale-claim:PT5M}") Duration staleClaim) {
        this.claims = claims;
        this.processor = processor;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.pendingMinAge = pendingMinAge;
        this.staleClaim = staleClaim;
    }

    @Scheduled(fixedDelayString = "${app.receipt.recovery-poll-ms:60000}")
    public void run() {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        List<UUID> claimed = claims.claim(owner, token, now, now.minus(pendingMinAge), now.minus(staleClaim),
                maxAttempts, batchSize);
        if (claimed.isEmpty()) {
            return;
        }
        log.info("Receipt recovery claimed {} stranded receipt(s)", claimed.size());

        for (UUID receiptId : claimed) {
            try {
                ReceiptProcessor.Outcome outcome = processor.process(receiptId);
                claims.markProcessed(receiptId, token);
                log.info("Receipt recovery outcome={} receiptId={}", outcome, receiptId);
            } catch (RuntimeException exception) {
                // Bounded safe code only: exception messages may carry SQL,
                // paths or credentials and never reach the receipt row.
                claims.markError(receiptId, token, PROCESSING_ERROR);
                log.warn("Receipt recovery failed receiptId={} error={}", receiptId,
                        exception.getClass().getSimpleName());
            }
        }
    }
}
