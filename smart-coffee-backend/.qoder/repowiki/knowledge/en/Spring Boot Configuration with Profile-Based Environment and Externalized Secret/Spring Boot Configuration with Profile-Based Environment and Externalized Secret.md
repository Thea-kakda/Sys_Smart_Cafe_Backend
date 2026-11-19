---
kind: configuration_system
name: Spring Boot Configuration with Profile-Based Environment and Externalized Secrets
category: configuration_system
scope:
    - '**'
source_files:
    - src/main/resources/application.yml
    - src/test/resources/application-test.yml
    - .env.example
    - compose.yaml
    - src/main/java/com/smartcoffee/config/SecurityConfig.java
    - src/main/java/com/smartcoffee/modules/auth/security/JwtProperties.java
    - src/main/java/com/smartcoffee/config/MinioConfig.java
    - src/main/java/com/smartcoffee/config/RabbitMqConfig.java
    - src/main/java/com/smartcoffee/config/RedisConfig.java
---

## Overview

The Smart Coffee backend uses a standard Spring Boot configuration system layered over `application.yml` with environment-variable substitution, profile-based overrides, and Java `@ConfigurationProperties` classes for typed access. Configuration is externalized via `.env.example`, Docker Compose environment variables, and per-profile YAML files.

## Core Files and Structure

- **Primary config**: `src/main/resources/application.yml` — defines all Spring Boot properties (datasource, JPA/Hibernate, Flyway, RabbitMQ publisher confirms), application-specific `app.*` keys (minio, security.jwt, catalog, cart, inventory, payment, notification, outbox, websocket, receipt), actuator exposure, and logging levels.
- **Test profile**: `src/test/resources/application-test.yml` — activates when the `test` profile is active; disables scheduling, sets DDL to `validate`, hardcodes a JWT secret, and whitelists WebSocket origins for tests.
- **Profile activation**: `spring.profiles.default: local` in `application.yml` means no explicit `-Dspring.profiles.active` is required to run locally.
- **Environment variable template**: `.env.example` documents the expected env vars (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `JWT_HMAC_SECRET_BASE64`, `MINIO_*`, `WEBSOCKET_ALLOWED_ORIGINS`, `RECEIPT_BUCKET`).
- **Infrastructure wiring**: `compose.yaml` provisions PostgreSQL (port 5433), Redis 7.4-alpine, RabbitMQ 4-management, and MinIO, exposing them on well-known ports so defaults in `application.yml` resolve without extra env vars.

## Configuration Loading Strategy

1. **Property sources order** follows Spring Boot defaults: `application.yml` → profile-specific YAMLs → OS environment variables → command-line args. All values use `${ENV_VAR:default}` syntax, so every property has a documented default.
2. **Typed configuration** is split between:
   - `application.yml` flat keys under `app.*` consumed by `@Value` injection (e.g. `MinioConfig` reads `app.minio.endpoint`, `app.minio.access-key`, `app.minio.secret-key`).
   - `JwtProperties` record (`@ConfigurationProperties(prefix = "app.security.jwt")`) bound to `app.security.jwt.secret`, `accessTokenMinutes`, `refreshTokenDays` and enabled via `@EnableConfigurationProperties(JwtProperties.class)` in `SecurityConfig`.
3. **Profiles**: The only profile file shipped is `application-test.yml`; production or staging profiles are not present in the repo and would be supplied externally at runtime.

## Key Conventions Observed

- **External secrets via env vars**: Database credentials, JWT HMAC secret, MinIO credentials, WebSocket allowed origins, and receipt bucket name are all read from environment variables with safe defaults. No secrets are committed to source control.
- **Per-domain tuning under `app.*`**: Each bounded context exposes its own tuning knobs (e.g. `app.cart.expiration-poll-ms`, `app.inventory.expiration-poll-ms`, `app.payment.expiration-poll-ms`, `app.notification.queue`, `app.outbox.batch-size`, `app.outbox.poll-delay-ms`). This keeps domain-specific behavior configurable without touching code.
- **Infrastructure defaults assume Docker Compose**: `application.yml` points at `127.0.0.1:5433` for Postgres and `localhost:9000` for MinIO, matching the `compose.yaml` port mappings. Tests rely on the same compose stack.
- **Actuator endpoints exposed**: Health probes, info, metrics, and Prometheus endpoints are explicitly included under `management.endpoints.web.exposure.include`.
- **Flyway migrations**: Enabled with `validate-on-migrate: true` and `clean-disabled: true`, reading from `classpath:db/migration`. This enforces that schema changes must be versioned and cannot be auto-cleaned.
- **Redis caching**: `RedisConfig` enables caching globally with a 10-minute default TTL and null-value eviction; Spring Data Redis repositories are disabled (`spring.data.redis.repositories.enabled: false`) because the app uses custom cache abstractions.
- **RabbitMQ publisher confirms**: `publisher-confirm-type: correlated` and `publisher-returns: true` ensure publish-side reliability; exchange name `smartcoffee.business.events` is declared as a constant in `RabbitMqConfig`.

## Constraints and Rules Enforced by Code

- **JWT secret must be base64-encoded HmacSHA256 key**: `SecurityConfig.jwtDecoder()` decodes `app.security.jwt.secret` via `Base64.getDecoder()` and constructs an `HmacSHA256` `SecretKeySpec`. Supplying a non-base64 value will fail at startup.
- **No stateful sessions**: `SecurityFilterChain` sets `SessionCreationPolicy.STATELESS`; all requests require authentication except the explicitly whitelisted paths (`/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/api/v1/auth/**`, `/api/v1/stores/**`, `/api/v1/categories/**`, `/api/v1/products/**`).
- **Hibernate DDL is disabled**: `ddl-auto: none` forces reliance on Flyway migrations; any schema drift requires a new migration file.
- **Open-in-view disabled**: `open-in-view: false` prevents lazy-loading outside transactional boundaries, enforcing explicit data-fetching patterns.
- **Test profile disables scheduling**: `spring.task.scheduling.enabled: false` in `application-test.yml` prevents background jobs from interfering with integration tests.

## Notable Gaps

- There is no dedicated `application-local.yml`, `application-prod.yml`, etc.; environment differences are handled entirely through environment variables and the single `application-test.yml` profile.
- No feature-flag framework is used; toggles are expressed as plain `app.*` properties (e.g. `app.cart.expiration-poll-ms`) rather than a structured flag registry.