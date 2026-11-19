package com.smartcoffee.modules.payment.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-node claim for the receipt recovery worker. Mirrors the proven
 * reservation-expiry claim convention (V047): a bounded
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} followed by an atomic claim
 * {@code UPDATE}, so two nodes never block each other and each receipt is
 * processed once per attempt. The caller processes each returned id in its own
 * transaction; nothing here holds a transaction across the batch.
 */
@Repository
public class ReceiptRecoveryClaimRepository {

    private final JdbcTemplate jdbc;

    public ReceiptRecoveryClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims up to {@code batchSize} stranded receipts: PENDING older than
     * {@code pendingBefore}, FAILED below the attempt ceiling whose exponential
     * backoff has elapsed, or a stale PROCESSING claim. Ordering is
     * deterministic (creation time, then id).
     */
    @Transactional
    public List<UUID> claim(String owner, String token, Instant now, Instant pendingBefore, Instant staleBefore,
            int maxAttempts, int batchSize) {
        List<UUID> ids = jdbc.query(
                """
                SELECT receipt_id
                FROM payment.receipts
                WHERE (processing_started_at IS NULL OR processing_started_at < ?)
                  AND (
                        (status = 'PENDING' AND created_at <= ?)
                     OR (status = 'FAILED'
                         AND attempts < ?
                         AND (last_attempt_at IS NULL
                              OR last_attempt_at + make_interval(
                                     secs => LEAST(300::double precision,
                                                   POWER(2, attempts)::double precision)) <= ?))
                     OR (status = 'PROCESSING' AND attempts < ?)
                  )
                ORDER BY created_at, receipt_id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                ts(staleBefore), ts(pendingBefore), maxAttempts, ts(now), maxAttempts, batchSize);

        if (ids.isEmpty()) {
            return List.of();
        }

        UUID[] idArray = ids.toArray(UUID[]::new);
        jdbc.update(
                """
                UPDATE payment.receipts
                SET processing_owner = ?,
                    processing_token = ?,
                    processing_started_at = current_timestamp
                WHERE receipt_id = ANY(?)
                """,
                owner, token, new SqlArrayValue("uuid", (Object[]) idArray));

        return ids;
    }

    /** Clears the claim on success, guarded by the claim token. */
    @Transactional
    public void markProcessed(UUID receiptId, String token) {
        jdbc.update(
                """
                UPDATE payment.receipts
                SET processing_owner = NULL,
                    processing_token = NULL,
                    processing_started_at = NULL
                WHERE receipt_id = ?
                  AND processing_token = ?
                """,
                receiptId, token);
    }

    /**
     * Releases the claim after an unexpected failure so the row becomes eligible
     * again (subject to the attempt ceiling and backoff).
     */
    @Transactional
    public void markError(UUID receiptId, String token, String error) {
        jdbc.update(
                """
                UPDATE payment.receipts
                SET processing_owner = NULL,
                    processing_token = NULL,
                    processing_started_at = NULL,
                    failure_reason = COALESCE(failure_reason, ?)
                WHERE receipt_id = ?
                  AND processing_token = ?
                """,
                truncate(error), receiptId, token);
    }

    private static SqlParameterValue ts(Instant value) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, value.atOffset(ZoneOffset.UTC));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
