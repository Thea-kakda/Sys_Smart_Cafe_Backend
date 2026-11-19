# ADR-004: Transactional Outbox and Consumer Inbox

- **Status:** Proposed
- **Decision date:** 2026-08-18
- **Owners:** Architecture and Backend Teams
- **Scope:** Reliable publication and consumption of integration events

## Context

A business transaction and broker publication cannot be committed atomically without a distributed transaction. Publishing after a database commit can lose an event if the process crashes. Publishing before commit can expose an event for data that later rolls back.

RabbitMQ uses at-least-once delivery, so consumers may receive duplicate messages.

## Decision

Use a PostgreSQL transactional outbox for durable integration events and a consumer inbox for idempotent consumption.

The business record and `outbox_events` row are inserted in the same local PostgreSQL transaction. A relay publishes unpublished events to RabbitMQ and marks them published after broker confirmation.

Each durable consumer inserts `(consumer_name, event_id)` into `consumer_inbox` within the same transaction as its business effect. A duplicate-key result means the event was processed previously.

## Outbox Contract

Every outbox event includes:

```text
event_id
aggregate_type
aggregate_id
event_type
event_version
correlation_id
causation_id
payload
occurred_at
published_at
publish_attempts
last_error
```

Event names use past tense and explicit versions, for example:

```text
OrderPlaced.v1
PaymentSucceeded.v1
InventoryReserved.v1
```

## Publisher Behavior

1. Select a bounded batch of unpublished events.
2. Use safe concurrent polling such as `FOR UPDATE SKIP LOCKED` where appropriate.
3. Publish with persistent messages and publisher confirmation.
4. Mark `published_at` only after confirmation.
5. Increment attempts and record bounded error details after failure.
6. Alert when unpublished event age exceeds the service objective.
7. Archive old published events according to retention policy.

## Consumer Behavior

1. Receive message.
2. Validate envelope and supported schema version.
3. Begin local transaction.
4. Insert inbox claim.
5. If already present, commit and acknowledge.
6. Apply business effect.
7. Write any new outbox events.
8. Commit.
9. Acknowledge the broker message.

## Alternatives Considered

### Dual write to PostgreSQL and RabbitMQ

Rejected because one side can succeed while the other fails.

### XA or two-phase commit

Rejected because of operational complexity, coupling, availability impact, and poor fit for future services.

### Change Data Capture immediately

Deferred. CDC can publish outbox rows later, but an application relay is simpler initially.

### Broker transactions only

Rejected because they do not atomically include the PostgreSQL business transaction.

## Consequences

### Positive

- No event is intentionally published for rolled-back data.
- Committed business changes eventually produce events.
- Consumers safely tolerate duplicates.
- Supports service extraction without distributed transactions.

### Negative

- Publication is eventually consistent.
- Outbox and inbox storage require cleanup and monitoring.
- Event version governance is mandatory.
- A relay component and operational alerts are required.

## Implementation Rules

- Do not publish critical RabbitMQ events directly from controllers.
- Do not mark an outbox record published without broker confirmation.
- Do not acknowledge a consumed event before the local transaction commits.
- Do not place secrets, passwords, raw tokens, or unnecessary PII in event payloads.
- Upcasters or parallel consumers must handle compatible event evolution.
- Consumers must tolerate duplicate and delayed events.

## Revisit Conditions

Consider CDC when event volume, publication latency, or operational standardization justifies it. The atomic outbox and inbox principles remain even if the relay technology changes.
