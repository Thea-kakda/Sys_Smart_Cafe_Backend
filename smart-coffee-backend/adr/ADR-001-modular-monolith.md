# ADR-001: Begin with a Modular Monolith

- **Status:** Proposed
- **Decision date:** 2026-08-18
- **Owners:** Architecture Team
- **Scope:** SMART Coffee & Bakery backend deployment and module boundaries

## Context

SMART Coffee & Bakery includes tightly related capabilities such as Store, Catalog, Cart, Ordering, Promotion, Payment, Recipe, Inventory, Loyalty, Kitchen, Notification, and Reporting.

Order checkout requires immediate validation of store availability, server-authoritative product pricing, customization rules, promotion eligibility, recipe requirements, and inventory availability. Splitting these capabilities into independently deployed services at the beginning would introduce distributed transactions, network failure modes, event ordering concerns, compensation logic, and additional operational cost before stable boundaries and traffic requirements are known.

The current codebase is named `identity-service`, but its folder structure includes nearly every operational domain. Therefore, it is not a dedicated Identity service.

## Decision

Begin with one Spring Boot 4 **modular monolith** for operational capabilities.

Use explicit domain modules:

```text
modules/
├── auth
├── user
├── rbac
├── store
├── catalog
├── customization
├── customer
├── loyalty
├── cart
├── order
├── payment
├── promotion
├── recipe
├── inventory
├── procurement
├── expense
├── kitchen
├── notification
├── analytics
├── audit
└── setting
```

The project should be renamed to `smart-coffee-backend` or `operation-service`. If renaming is temporarily impossible, document `identity-service` as a transitional repository name.

Modules communicate through explicit application interfaces and domain or integration events. One module must not directly inject another module's JPA repository.

## Alternatives Considered

### Independent microservices from the beginning

Rejected because it adds premature complexity, distributed failure modes, infrastructure overhead, and difficult consistency management.

### Unstructured monolith

Rejected because shared repositories, entities, and services would create strong coupling and make future extraction expensive.

### Serverless functions per feature

Rejected because core checkout and inventory transactions require predictable transaction boundaries and coordinated domain behavior.

## Consequences

### Positive

- Local PostgreSQL transactions protect checkout correctness.
- Development, testing, and deployment are simpler.
- Module boundaries can be validated before extraction.
- Lower operational cost for the initial team.
- Easier end-to-end debugging.

### Negative

- One deployment can affect multiple modules.
- Modules share compute resources.
- Architectural tests and reviews are required to prevent boundary erosion.
- Some modules cannot scale independently initially.

## Implementation Rules

1. Every table has exactly one write-owning module.
2. Controllers never contain business logic.
3. Application services define transaction boundaries.
4. Business state transitions belong to aggregates or domain services.
5. Modules do not access another module's repository directly.
6. Cross-module communication uses application ports or documented events.
7. Package dependency rules should be enforced with architecture tests.
8. Notification and reporting failures must not roll back committed orders.

## Future Extraction Candidates

Likely extraction order:

1. Identity and Access
2. Payment
3. Notification
4. Analytics
5. Inventory and Procurement
6. Ordering
7. Catalog

## Revisit Conditions

Revisit when one or more conditions exist:

- Separate teams need independent ownership and release schedules.
- A module requires materially different scaling.
- Payment or identity requires stronger security isolation.
- A module's outages must not affect checkout.
- Deployment frequency causes unacceptable coordination.
- Stable APIs, events, and database ownership have already been proven.
