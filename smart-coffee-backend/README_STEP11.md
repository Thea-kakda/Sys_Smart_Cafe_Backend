# Backend Step 11: Notifications and Audit

Overlay onto Steps 1 to 10. APIs: GET `/api/v1/notifications`, GET `/api/v1/notifications/unread-count`, PATCH `/api/v1/notifications/{id}/read`, PATCH `/api/v1/notifications/read-all`, GET `/api/v1/audit-logs`.

Notifications persist before external delivery. Recipient ownership protects reads. Delivery attempts use bounded retry. Consumer Inbox and unique source-event constraints suppress duplicates. Replace placeholder device-token and email resolution before production.

Audit logs are append-only and use explicit redacted snapshots. Apply BUSINESS_AUDIT_PATCH.md and RABBITMQ_NOTIFICATION_PATCH.md. V010 assumes V001-V009. Verify Flyway history before applying.
