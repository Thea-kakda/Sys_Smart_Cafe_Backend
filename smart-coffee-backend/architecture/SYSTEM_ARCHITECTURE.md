# SMART Coffee & Bakery Management Platform

## System Architecture

**Status:** Proposed for architecture approval  
**Architecture style:** Modular Monolith First, Microservice-Ready  
**Backend:** Java 25-compatible, Spring Boot 4, Spring Web MVC  
**Primary database:** PostgreSQL 16+  
**Cache:** Redis  
**Messaging:** RabbitMQ  
**Object storage:** MinIO locally, S3-compatible storage in production

---

## 1. Purpose

This document defines the initial system architecture, module boundaries, database ownership, communication rules, transaction boundaries, and future extraction strategy for the SMART Coffee & Bakery Management Platform.

It is an architecture contract. It does not authorize implementations to bypass module boundaries or directly modify another module's tables.

---

## 2. Architecture Decision

The platform begins as a **modular monolith** because Store, Catalog, Cart, Ordering, Promotion, Payment, Recipe, and Inventory require closely coordinated business transactions.

The initial application is one Spring Boot deployable with clearly separated domain modules:

```text
smart-coffee-backend
├── identity and access
├── store
├── catalog and customization
├── customer and loyalty
├── cart
├── ordering
├── payment
├── promotion
├── recipe and inventory
├── procurement
├── expense
├── kitchen
├── notification
├── analytics
├── audit
└── settings
```

The current repository may temporarily retain the name `identity-service`, but that name does not represent its actual responsibility. A repository containing all operational modules is a backend modular monolith, not a dedicated Identity microservice.

---

## 3. System Context

### Client applications

- Customer Mobile App
- Customer Web Application
- Staff POS Application
- Administrative Dashboard
- Kitchen Display System
- Approved partner integrations

### External systems

- KHQR/Bakong payment provider
- Push notification provider
- Email provider
- S3-compatible object storage

### Platform responsibilities

- Authentication and authorization
- Multi-store access enforcement
- Catalog and server-authoritative pricing
- Cart and checkout
- Order lifecycle
- Payment attempts, callbacks, refunds, and reconciliation
- Recipe-based inventory reservation and consumption
- Promotion and loyalty accounting
- Kitchen workflow
- Notification delivery
- Reporting projections
- Audit trails

---

## 4. Runtime Containers

### 4.1 Spring Boot backend

The backend exposes `/api/v1/**` REST APIs and secured STOMP/WebSocket destinations. It contains all initial business modules and owns the operational PostgreSQL database.

### 4.2 PostgreSQL

PostgreSQL is the durable source of truth for:

- Identity and sessions
- Business records
- Payment records
- Inventory state and ledger
- Idempotency records
- Transactional outbox
- Consumer inbox
- Audit records

Redis must never be the only source of truth for orders, payments, refunds, stock, promotion usage, or loyalty points.

### 4.3 Redis

Approved Redis uses:

- Bounded catalog and store cache
- Rate limiting
- Optional session revocation cache backed by PostgreSQL
- Short-lived coordination and cache invalidation

### 4.4 RabbitMQ

RabbitMQ supports durable asynchronous work such as:

- Notification delivery
- Receipt generation
- Reporting projection updates
- Retryable external integrations
- Background processing

Critical events must use a transactional outbox. Consumers must use an inbox or equivalent durable deduplication.

### 4.5 MinIO/S3

Object storage holds product images, receipts, and authorized uploaded documents. The database stores object keys and metadata, not raw secrets or unbounded binary data.

---

## 5. Module Boundaries and Ownership

### Identity and Access

Owns users, credentials, sessions, roles, permissions, and store-scoped role assignments.

It does not own Stores, Orders, Payments, or Inventory. `store_id` in a future extracted Identity service becomes a logical external reference.

### Store

Owns stores and opening hours. It provides store timezone, currency policy, active status, and operational availability.

### Catalog and Customization

Owns categories, products, store-specific product availability, effective prices, customization groups, and customization options.

Ordering captures immutable product, price, tax, and customization snapshots. Historical orders never depend on current catalog values.

### Customer and Loyalty

Customer owns profile, consent, and favorites. Loyalty owns an immutable points ledger and any cached balance projection.

### Cart

Owns active customer/store carts and cart lines. A single cart belongs to exactly one customer and one store.

Cart values are estimates. Checkout recalculates all prices authoritatively.

### Ordering

Owns orders, order items, snapshots, status history, and order idempotency results.

Ordering coordinates checkout. It does not accept price totals from clients and does not mark external payments successful.

### Payment

Owns payment aggregates, attempts, provider callbacks, refunds, reconciliation, and receipts.

Payment success must come from trusted server-side cash acceptance or verified provider evidence.

### Promotion

Owns promotion rules and redemption records. Usage limits are enforced transactionally.

### Recipe and Inventory

Recipe owns product-to-ingredient requirements and version history. Inventory owns balances, reservations, lots, and immutable stock movements.

### Notification

Owns notification requests, recipients, delivery attempts, device tokens, and retry state. Delivery failure must not roll back an order or payment.

### Analytics

Owns reporting projections. It consumes events and must not update operational records.

### Audit

Owns append-only security and business audit records. Ordinary application code cannot update or delete audit history.

---

## 6. Communication Rules

### Synchronous calls

Use direct application interfaces inside the modular monolith for operations requiring an immediate decision:

- Checkout reads authoritative catalog data
- Ordering requests promotion evaluation
- Ordering requests inventory reservation
- Payment queries order balance
- Kitchen validates order transition

Modules must not directly inject another module's JPA repository.

### In-process events

Spring application events may be used for non-critical reactions inside one process. They are not sufficient for events that must survive process failure unless paired with durable persistence.

### Durable events

Use transactional outbox plus RabbitMQ for:

- `OrderPlaced.v1`
- `OrderConfirmed.v1`
- `PaymentSucceeded.v1`
- `PaymentFailed.v1`
- `InventoryReserved.v1`
- `InventoryConsumed.v1`
- `NotificationRequested.v1`

Delivery is at least once. Every consumer must be idempotent.

---

## 7. Transaction Boundaries

### Order placement transaction

In the initial modular monolith, order creation and inventory reservation use one local PostgreSQL transaction:

1. Claim `Idempotency-Key`.
2. Verify request hash.
3. Validate store and authorization.
4. Load server-authoritative catalog and customization data.
5. Evaluate promotion and create redemption reservation.
6. Calculate subtotal, discount, tax, rounding, and total.
7. Reserve inventory atomically.
8. Persist order and immutable line snapshots.
9. Write outbox event.
10. Store idempotency result.
11. Commit.

No external payment request may execute while this database transaction remains open.

### Payment transaction

A payment request creates a payment attempt and outbox record transactionally. The external provider call occurs outside the database transaction. Provider callbacks are verified, deduplicated, and applied transactionally.

### Inventory transaction

All ingredient reservations for an order succeed or fail together. Partial reservations are rolled back.

---

## 8. Consistency Model

### Strong consistency

Required for:

- Credential and refresh-token rotation
- Order idempotency
- Server-authoritative price calculation
- Promotion usage and redemption
- Payment state transitions
- Refund limits
- Inventory reservation and stock ledger
- Loyalty ledger entries
- Store-scoped authorization decisions

### Eventual consistency

Acceptable for:

- Notification delivery
- Analytics dashboards
- Search indexes
- Catalog cache invalidation
- Receipt rendering after payment persistence
- Non-authoritative WebSocket updates

---

## 9. Security Trust Boundaries

- Clients are untrusted.
- API prices, totals, roles, store IDs, and payment results are never trusted without server validation.
- Every store-sensitive use case verifies authenticated user, requested store, and permission.
- Refresh tokens are accepted only at the refresh endpoint and stored only as hashes.
- KHQR callbacks require provider signature validation, freshness checks, replay protection, and a unique provider event identifier.
- WebSocket subscriptions require per-order or per-store authorization.
- Secrets are externalized and never stored in source code or ordinary editable settings.

---

## 10. Offline POS Policy

The POS may record controlled offline operations using a unique `(device_id, local_operation_id)` pair.

Rules:

- Offline cash sales may be allowed by store policy.
- KHQR, card, and wallet payments cannot be marked paid without trusted provider evidence.
- Central inventory is not directly decremented by the offline client.
- Synchronization is idempotent.
- Pricing or promotion conflicts are flagged for supervisor review.
- Local records must be encrypted and tamper-evident where practical.

---

## 11. Future Microservice Extraction

Likely extraction order:

1. Identity and Access
2. Payment
3. Notification
4. Analytics
5. Inventory and Procurement
6. Ordering
7. Catalog

Extraction criteria include independent scaling, stronger security isolation, team ownership, release cadence, and fault isolation.

After extraction:

- Each service owns its database.
- No cross-service foreign keys are permitted.
- IDs across services are logical references.
- No distributed database transactions or two-phase commit are permitted.
- Sagas use idempotent commands, outbox/inbox, compensation, and reconciliation.

---

## 12. Architecture Approval Checklist

- [ ] Project naming matches actual deployed responsibility
- [ ] Every table has exactly one write owner
- [ ] Module dependencies are accepted
- [ ] Corrected DBML is approved
- [ ] Order, payment, refund, and inventory state machines are approved
- [ ] Idempotency model is approved
- [ ] KHQR callback security is approved
- [ ] Offline POS policy is approved
- [ ] RabbitMQ outbox/inbox strategy is approved
- [ ] Reporting is accepted as an eventual-consistency projection
- [ ] Future service extraction has no cross-database foreign keys

---

## 13. Implementation Gate

Only after this architecture, database ownership, corrected DBML, and critical flows are approved should Phase 1 create Maven configuration, application profiles, Docker, PostgreSQL/Flyway, Redis, RabbitMQ, MinIO, exception handling, request tracing, validation, Actuator, and Testcontainers.
