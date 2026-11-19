---
kind: logging_system
name: SLF4J-Based Structured Logging with Correlation Context and Request Filter
category: logging_system
scope:
    - '**'
source_files:
    - src/main/resources/application.yml
    - src/main/java/com/smartcoffee/infrastructure/observability/RequestLoggingFilter.java
    - src/main/java/com/smartcoffee/common/context/CorrelationContext.java
    - src/main/java/com/smartcoffee/common/context/RequestContext.java
    - src/main/java/com/smartcoffee/infrastructure/notification/LoggingPushNotificationGateway.java
    - src/main/java/com/smartcoffee/infrastructure/messaging/deadletter/DeadLetterService.java
    - src/main/java/com/smartcoffee/infrastructure/cache/CacheInvalidationService.java
    - src/main/java/com/smartcoffee/infrastructure/messaging/outbox/ConfirmedOutboxPublisher.java
---

## What system/approach is used

The application uses **SLF4J** as the logging facade, backed by the default Logback implementation provided by `spring-boot-starter-web` (no explicit logback dependency in `pom.xml`). There are no third-party logging frameworks (Log4j2, java.util.logging) or annotation processors like Lombok's `@Slf4j`; every logger is created via `LoggerFactory.getLogger(...)`. The project does not define a custom `logback-spring.xml`, so all runtime behavior is driven by Spring Boot defaults plus the `logging.level.*` entries in `application.yml`.

## Key files and packages

- `src/main/resources/application.yml` — global logging configuration; sets `logging.level.org.flywaydb: DEBUG` and leaves everything else at Spring Boot defaults.
- `src/main/java/com/smartcoffee/infrastructure/observability/RequestLoggingFilter.java` — an `OncePerRequestFilter` that logs one structured line per HTTP request with fields `method`, `path`, `status`, `durationMs`.
- `src/main/java/com/smartcoffee/common/context/CorrelationContext.java` + `RequestContext.java` — a `ThreadLocal`-based holder for `requestId`, `correlationId`, `locale`, `clientIp`, `userAgent` intended to be included in log output.
- `src/main/java/com/smartcoffee/infrastructure/notification/LoggingPushNotificationGateway.java` — a `@Profile("!local & !test")` component that logs push-notification attempts with token masking (`maskToken`) to avoid leaking secrets.
- `src/main/java/com/smartcoffee/infrastructure/messaging/deadletter/DeadLetterService.java` — logs dead-lettered events at `error` level with `eventId` and `code` fields.
- `src/main/java/com/smartcoffee/infrastructure/cache/CacheInvalidationService.java` — uses `debug`/`warn` levels for cache eviction outcomes.
- `src/main/java/com/smartcoffee/infrastructure/messaging/outbox/ConfirmedOutboxPublisher.java` — logs outbox publishing warnings.

## Architecture and conventions

1. **Logger instantiation pattern**: Every class declares `private static final Logger log = LoggerFactory.getLogger(ClassName.class);`. No annotation-based logger injection is used anywhere in the codebase.

2. **Structured key=value log lines**: All observed log statements use SLF4J parameterized messages with named keys separated by spaces, e.g. `"http method={} path={} status={} durationMs={}"`, `"Dead-letter eventId={} code={}"`, `"Evicted key '{}' from cache '{}'"`. This produces flat, parseable lines suitable for log aggregation without JSON formatting.

3. **Level usage convention**:
   - `info` — normal operational flow (HTTP requests, push notification delivery).
   - `warn` — recoverable anomalies (cache miss on evict, outbox publish retries).
   - `error` — unrecoverable failures (dead-letter queue recording).
   - `debug` — low-volume diagnostic traces (cache eviction details).

4. **Cross-cutting observability layer**: Logging is centralized under `infrastructure.observability` (the `RequestLoggingFilter`) rather than scattered across controllers, keeping request-level metrics in one place.

5. **Correlation context exists but is not yet wired into log output**: `CorrelationContext` exposes `requestId()` and stores `correlationId`, `clientIp`, `userAgent` in a `ThreadLocal`, but none of the current log statements call `CorrelationContext.requestId()`. The infrastructure is in place for future correlation-id enrichment of log lines.

6. **Secrets handling in logs**: The `LoggingPushNotificationGateway` masks tokens before logging them, demonstrating a convention of never emitting raw secrets even in `info`-level logs.

7. **No per-module loggers**: Business modules (`modules/*`) do not currently emit logs directly; logging is concentrated in infrastructure components (messaging, cache, observability, notifications).

## Conventions and constraints

- **Single facade**: Only `org.slf4j.Logger` / `LoggerFactory` is used; there is no direct Logback, Log4j2, or `java.util.logging` usage in production code.
- **No custom layout or appenders**: Because no `logback-spring.xml` exists, log format, sinks, and rotation are entirely delegated to Spring Boot's auto-configured Logback setup.
- **Level overrides via properties only**: The only programmatic control over log verbosity is through `application.yml`'s `logging.level.*` entries (currently only Flyway is set to `DEBUG`).
- **Profile-scoped logging components**: Some logging-only implementations (e.g. `LoggingPushNotificationGateway`) are gated behind `@Profile("!local & !test")`, meaning they are active in non-local/non-test environments — a convention that lets logging behavior vary by deployment profile.
- **Key-value field naming**: When adding new log lines, follow the existing `key={} value={}` style with lowercase snake_case keys (e.g. `durationMs`, `clientId`, `storeId`) so downstream parsers can extract fields consistently.