package com.smartcoffee.modules.inventory.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-node claim for the ORDER reservation expiry worker (Slice 2E, spec §5/§6).
 * Deliberately a clone of {@code JdbcOutboxClaimRepository}'s proven convention:
 * a bounded {@code SELECT ... FOR UPDATE SKIP LOCKED} followed by an atomic claim
 * {@code UPDATE}, so two nodes running the same worker never block each other and
 * each reservation is processed once per attempt.
 *
 * <p>The claim lives on the reservation row itself ({@code processing_*} columns,
 * V047) rather than a separate job table. {@code staleBefore} reclaims rows whose
 * previous owner died mid-batch; {@code maxAttempts} is a dead-letter ceiling so a
 * reservation that keeps failing transiently stops being hot-looped.
 *
 * <p>The caller processes each returned id in its OWN transaction (via
 * {@link com.smartcoffee.infrastructure.retry.TransientRetryExecutor}); nothing
 * here holds a transaction across the batch.
 */
@Repository
public class ReservationExpiryClaimRepository {

    private final JdbcTemplate jdbc;

    public ReservationExpiryClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims up to {@code batchSize} expired, unclaimed (or stale) order-owned
     * ACTIVE reservations for {@code owner}. Returns their ids in expiry order.
     */
    @Transactional
    public List<UUID> claimExpiredOrder(String owner, String token, Instant now, Instant staleBefore, int batchSize,
            int maxAttempts) {
        SqlParameterValue nowParam = ts(now);
        SqlParameterValue staleParam = ts(staleBefore);

        List<UUID> ids = jdbc.query(
                """
                SELECT reservation_id
                FROM inventory.inventory_reservations
                WHERE source_type = 'ORDER'
                  AND status = 'ACTIVE'
                  AND expires_at <= ?
                  AND processing_attempts < ?
                  AND (processing_started_at IS NULL OR processing_started_at < ?)
                ORDER BY expires_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                nowParam, maxAttempts, staleParam, batchSize);

        if (ids.isEmpty()) {
            return List.of();
        }

        UUID[] idArray = ids.toArray(UUID[]::new);

        jdbc.update(
                """
                UPDATE inventory.inventory_reservations
                SET processing_owner = ?,
                    processing_token = ?,
                    processing_started_at = current_timestamp,
                    processing_attempts = processing_attempts + 1
                WHERE reservation_id = ANY(?)
                """,
                owner, token, new SqlArrayValue("uuid", (Object[]) idArray));

        return ids;
    }

    /** Clears the claim on success, guarded by the claim token (no clobber of a re-claim). */
    @Transactional
    public void markProcessed(UUID reservationId, String token) {
        jdbc.update(
                """
                UPDATE inventory.inventory_reservations
                SET processing_owner = NULL,
                    processing_token = NULL,
                    processing_started_at = NULL,
                    last_processing_error = NULL
                WHERE reservation_id = ?
                  AND processing_token = ?
                """,
                reservationId, token);
    }

    /**
     * Records a transient failure and releases the claim so the row is eligible
     * again on the next poll (until {@code maxAttempts} is reached). Guarded by
     * the claim token.
     */
    @Transactional
    public void markError(UUID reservationId, String token, String error) {
        jdbc.update(
                """
                UPDATE inventory.inventory_reservations
                SET processing_owner = NULL,
                    processing_token = NULL,
                    processing_started_at = NULL,
                    last_processing_error = ?
                WHERE reservation_id = ?
                  AND processing_token = ?
                """,
                truncate(error), reservationId, token);
    }

    private static SqlParameterValue ts(Instant value) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, value.atOffset(ZoneOffset.UTC));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    // Silence the unused-import warning for OffsetDateTime while documenting the
    // pgjdbc TIMESTAMPTZ limitation (reads elsewhere go through OffsetDateTime).
    @SuppressWarnings("unused")
    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
