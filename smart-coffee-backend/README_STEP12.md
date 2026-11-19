# Backend Step 12: Infrastructure Hardening and Test Environment

Overlay onto Steps 1 to 11. Apply OUTBOX_ENTITY_PATCH.md and RECEIPT_ENTITY_PATCH.md. Remove or disable the earlier basic `OutboxPublisher`, `OutboxRecoveryJob`, and duplicate `WebSocketConfig` after adopting these replacements.

Includes durable RabbitMQ topology, PostgreSQL `FOR UPDATE SKIP LOCKED` Outbox claiming, publisher confirmations, race-safe Consumer Inbox insertion, WebSocket Store-subscription authorization, MinIO receipt processing foundation, readiness health, Docker Compose services, Testcontainers base, and Failsafe dependency fragments.

The starter receipt renderer creates text. Replace it with a real PDF renderer before production. Never use local default credentials in production.
