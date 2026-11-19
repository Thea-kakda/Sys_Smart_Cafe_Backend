package com.smartcoffee.infrastructure.messaging.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RaceSafeConsumerInboxService {

    private final JdbcTemplate jdbc;

    public RaceSafeConsumerInboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean processOnce(
            String consumer,
            UUID eventId,
            String eventType,
            int eventVersion,
            Runnable action
    ) {
        if (consumer == null || consumer.isBlank()) {
            throw new IllegalArgumentException("Consumer name is required");
        }

        if (eventId == null) {
            throw new IllegalArgumentException("Event ID is required");
        }

        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Event type is required");
        }

        if (eventVersion <= 0) {
            throw new IllegalArgumentException(
                    "Event version must be greater than zero"
            );
        }

        if (action == null) {
            throw new IllegalArgumentException("Consumer action is required");
        }

        int inserted = jdbc.update("""
                INSERT INTO integration.consumer_inbox
                (
                    inbox_id,
                    consumer_name,
                    event_id,
                    event_type,
                    event_version,
                    status,
                    received_at,
                    created_at,
                    updated_at,
                    version
                )
                VALUES
                (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'PROCESSING',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,
                    0
                )
                ON CONFLICT (consumer_name, event_id)
                DO NOTHING
                """,
                UUID.randomUUID(),
                consumer,
                eventId,
                eventType,
                eventVersion
        );

        // Event ážŠážŠáŸ‚áž›ážáŸ’ážšáž¼ážœáž”áž¶áž“ Insert áž¬ Process ážšáž½áž…áž áž¾áž™áŸ”
        if (inserted == 0) {
            return false;
        }

        /*
         * action.run() áž“áŸ…áž€áŸ’áž“áž»áž„ Transaction ážŠáž¼áž…áž‚áŸ’áž“áž¶áŸ”
         * áž”áž¾ action throw exception:
         *   - Business changes rollback
         *   - consumer_inbox insertion rollback
         *
         * RabbitMQ áž¢áž¶áž… retry message áž áž¾áž™ event áž¢áž¶áž… process áž˜áŸ’ážáž„áž‘áŸ€ážáŸ”
         */
        action.run();

        int updated = jdbc.update("""
                UPDATE integration.consumer_inbox
                SET status = 'PROCESSED',
                    processed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE consumer_name = ?
                  AND event_id = ?
                  AND status = 'PROCESSING'
                """,
                consumer,
                eventId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Failed to mark consumer inbox event as processed: "
                            + eventId
            );
        }

        return true;
    }
}
