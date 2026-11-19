# ADR-002: PostgreSQL Ownership and Database Boundaries

- **Status:** Proposed
- **Decision date:** 2026-08-18
- **Owners:** Architecture and Database Teams
- **Scope:** Persistent data ownership and future service extraction

## Context

The original schema uses one connected relational model with foreign keys across Identity, Store, Catalog, Ordering, Payment, Inventory, Notification, and Audit. This is appropriate for an initial modular monolith, but unsafe if independently deployed services share and mutate the same tables.

The platform requires strong correctness for credentials, order idempotency, payment state, refunds, promotion usage, loyalty points, and inventory reservations.

## Decision

PostgreSQL 16 or newer is the durable source of truth.

During the modular-monolith phase, use one PostgreSQL database with schemas aligned to domain ownership:

```text
identity
store
customer
catalog
cart
ordering
payment
promotion
inventory
procurement
expense
kitchen
notification
setting
audit
integration
reporting
```

Every table has exactly one write owner. Cross-schema foreign keys are allowed only while modules are deployed together and share one database transaction boundary.

After a module is extracted into a microservice:

- It receives an independently owned database or isolated database boundary.
- Cross-service foreign keys are removed.
- External identifiers remain UUID logical references.
- Other services cannot connect with write credentials.
- Data exchange occurs through APIs and versioned events.
- Reporting uses downstream projections rather than operational cross-database joins.

## Alternatives Considered

### Shared database for all future microservices

Rejected because ownership becomes ambiguous, deployments remain coupled, and services can bypass each other's invariants.

### Database per module immediately

Rejected for the initial modular monolith because it would prevent safe local transactions for checkout and add distributed consistency problems too early.

### NoSQL as the primary database

Rejected because the business model is relational and requires constraints, transactional consistency, exact monetary records, and auditable ledgers.

## Consequences

### Positive

- Strong relational constraints during the initial phase.
- Clear ownership supports future extraction.
- PostgreSQL transactions protect critical invariants.
- Service credentials can enforce access boundaries later.
- Reporting cannot accidentally become an operational writer.

### Negative

- Cross-schema foreign keys require planned removal during extraction.
- Schema ownership must be documented and reviewed.
- Large archival tables may eventually need partitioning.
- Data duplication is expected in reporting and service read models.

## Data Rules

1. Use UUID primary keys for business entities.
2. Use bigint identity keys for high-volume append-only history where appropriate.
3. Use `timestamptz` for business timestamps.
4. Use local `time` only for opening hours interpreted with store timezone.
5. Use `numeric` or exact minor-unit integers for money, never floating point.
6. Use `jsonb` for queried structured payloads.
7. Do not cascade-delete order, payment, stock, loyalty, or audit history.
8. Stock, loyalty, and audit ledgers are append-only.
9. Redis is never the only source of critical state.
10. Flyway owns all schema changes.

## Migration Rules

- Never modify an applied Flyway migration.
- Use additive migrations where possible.
- Use expand-and-contract for breaking changes.
- Backfill before making new columns mandatory.
- Validate constraints after cleanup when legacy data exists.
- Test every migration against a production-like PostgreSQL container.

## Revisit Conditions

Revisit physical database separation when a module is extracted, requires independent scaling, or needs stronger compliance/security isolation. The one-write-owner rule is permanent and is not revisited.
