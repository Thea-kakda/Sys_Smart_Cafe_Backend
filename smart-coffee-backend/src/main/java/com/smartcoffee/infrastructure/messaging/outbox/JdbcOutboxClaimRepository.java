package com.smartcoffee.infrastructure.messaging.outbox;

import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOutboxClaimRepository
        implements OutboxClaimRepository {

    private final JdbcTemplate jdbc;

    public JdbcOutboxClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public List<OutboxEvent> claim(
            String owner,
            int batchSize,
            Instant staleBefore
    ) {
        SqlParameterValue staleBeforeParameter =
                new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE,
                        staleBefore.atOffset(ZoneOffset.UTC)
                );

        List<UUID> ids = jdbc.query(
                """
                SELECT outbox_event_id
                FROM integration.outbox_events
                WHERE (
                    status = 'PENDING'
                    AND available_at <= current_timestamp
                )
                OR (
                    status = 'PROCESSING'
                    AND claimed_at < ?
                )
                ORDER BY occurred_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                staleBeforeParameter,
                batchSize
        );

        if (ids.isEmpty()) {
            return List.of();
        }

        UUID[] idArray = ids.toArray(UUID[]::new);

        jdbc.update(
                """
                UPDATE integration.outbox_events
                SET
                    status = 'PROCESSING',
                    claimed_by = ?,
                    claimed_at = current_timestamp,
                    attempt_count = attempt_count + 1
                WHERE outbox_event_id = ANY(?)
                """,
                owner,
                new SqlArrayValue("uuid", (Object[]) idArray)
        );

        return jdbc.query(
                """
                SELECT
                    outbox_event_id,
                    event_id,
                    event_type,
                    event_version,
                    aggregate_type,
                    aggregate_id,
                    routing_key,
                    payload,
                    status,
                    occurred_at,
                    available_at,
                    published_at,
                    attempt_count,
                    version
                FROM integration.outbox_events
                WHERE outbox_event_id = ANY(?)
                ORDER BY occurred_at
                """,
                this::map,
                new SqlArrayValue("uuid", (Object[]) idArray)
        );
    }

    @Override
    @Transactional
    public void markPublished(UUID id) {
        jdbc.update(
                """
                UPDATE integration.outbox_events
                SET
                    status = 'PUBLISHED',
                    published_at = current_timestamp,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    updated_at = current_timestamp
                WHERE outbox_event_id = ?
                  AND status = 'PROCESSING'
                """,
                id
        );
    }

    @Override
    @Transactional
    public void markRetry(UUID id, String error) {
        jdbc.update(
                """
                UPDATE integration.outbox_events
                SET
                    status = CASE
                        WHEN attempt_count >= 10 THEN 'FAILED'
                        ELSE 'PENDING'
                    END,
                    available_at =
                        current_timestamp
                        + (
                            least(
                                300,
                                power(2, least(attempt_count, 8))
                            ) || ' seconds'
                        )::interval,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    last_error_code = ?,
                    last_error_message = ?,
                    updated_at = current_timestamp
                WHERE outbox_event_id = ?
                """,
                "OUTBOX_PUBLISH_FAILED",
                error,
                id
        );
    }

    private OutboxEvent map(
            ResultSet rs,
            int rowNum
    ) throws java.sql.SQLException {
        return OutboxEvent.rehydrate(
                rs.getObject("outbox_event_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("routing_key"),
                rs.getString("payload"),
                OutboxStatus.valueOf(rs.getString("status")),
                toInstant(rs.getObject("occurred_at", OffsetDateTime.class)),
                toInstant(rs.getObject("available_at", OffsetDateTime.class)),
                toInstant(rs.getObject("published_at", OffsetDateTime.class)),
                rs.getInt("attempt_count"),
                rs.getLong("version")
        );
    }

    /**
     * pgjdbc does not support getObject(col, Instant.class) on TIMESTAMPTZ
     * ("conversion to class java.time.Instant from timestamptz not supported"),
     * so timestamps are read as OffsetDateTime and normalized to Instant here.
     */
    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}