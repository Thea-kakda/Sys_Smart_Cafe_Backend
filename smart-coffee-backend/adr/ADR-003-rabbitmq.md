# ADR-003: Use RabbitMQ for Initial Durable Messaging

- **Status:** Proposed
- **Decision date:** 2026-08-18
- **Owners:** Architecture and Platform Teams
- **Scope:** Asynchronous business processing and integration events

## Context

The platform needs asynchronous processing for notification delivery, receipt generation, reporting projections, provider retries, and background jobs. Initial event volume is expected to be moderate, while routing, retries, acknowledgements, and dead-letter handling are important.

Kafka offers durable event streams and replay at high scale, but introduces additional operational and design complexity that is not currently justified.

## Decision

Use RabbitMQ as the initial durable message broker.

Use Spring application events for simple in-process reactions that do not require durable delivery. Use RabbitMQ only when work must survive process failure, cross a deployable boundary, or use delayed retry and dead-letter handling.

Critical producer events must be written through the transactional outbox. Critical consumers must use durable inbox deduplication.

## Initial Message Categories

```text
order.events
payment.events
inventory.events
identity.events
notification.commands
reporting.events
```

Recommended routing keys:

```text
order.placed.v1
order.confirmed.v1
payment.succeeded.v1
payment.failed.v1
inventory.reserved.v1
inventory.consumed.v1
notification.requested.v1
```

## Alternatives Considered

### Kafka

Deferred because the initial platform does not yet require very high throughput, long event retention, stream processing, or broad replay by many consumer groups.

### Spring application events only

Rejected for durable workflows because events are lost when the process fails and do not naturally cross service boundaries.

### Direct asynchronous HTTP calls

Rejected for non-immediate work because callers become coupled to downstream availability and retry behavior.

### Database polling without a broker

Acceptable as a temporary internal job mechanism, but not selected as the long-term integration channel.

## Delivery Semantics

RabbitMQ delivery is treated as **at least once**.

Therefore:

1. Every message has a globally unique `eventId`.
2. Consumers claim `(consumerName, eventId)` in `consumer_inbox`.
3. Duplicate messages return success without reapplying effects.
4. Acknowledgement occurs only after the consumer transaction commits.
5. Event ordering is guaranteed only where explicitly designed.
6. Aggregate ID should be used consistently for routing/ordering design.

## Retry and Dead-Letter Policy

- Retry only transient failures.
- Use exponential backoff with jitter.
- Do not retry validation or permanent business failures.
- Bound automatic attempts.
- Route exhausted messages to a domain-specific dead-letter queue.
- Alert on DLQ depth and oldest-message age.
- Provide a controlled replay procedure.
- Never blindly retry non-idempotent payment-provider operations.

## Consequences

### Positive

- Strong support for work queues, routing, retries, and acknowledgements.
- Lower operational complexity than Kafka for the initial scope.
- Suitable for notifications and background processing.
- Supports later service extraction.

### Negative

- Historical replay is less natural than Kafka.
- Ordering requires careful topology and consumer design.
- Broker operations, monitoring, and DLQ processes are still required.
- At-least-once delivery requires idempotent consumers.

## Revisit Conditions

Evaluate Kafka when:

- Event throughput or retention grows materially.
- Multiple analytical consumers need independent replay.
- Stream processing becomes a core requirement.
- A durable business event log is required for long periods.
- RabbitMQ routing and queue topology become unsuitable for workload shape.
