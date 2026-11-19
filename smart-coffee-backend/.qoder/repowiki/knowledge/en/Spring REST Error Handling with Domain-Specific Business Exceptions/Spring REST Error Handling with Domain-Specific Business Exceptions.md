---
kind: error_handling
name: Spring REST Error Handling with Domain-Specific Business Exceptions
category: error_handling
scope:
    - '**'
source_files:
    - src/main/java/com/smartcoffee/common/api/GlobalExceptionHandler.java
    - src/main/java/com/smartcoffee/common/api/ApiErrorResponse.java
    - src/main/java/com/smartcoffee/common/api/ValidationError.java
    - src/main/java/com/smartcoffee/common/exception/BusinessException.java
    - src/main/java/com/smartcoffee/common/exception/ResourceNotFoundException.java
    - src/main/java/com/smartcoffee/common/exception/UnauthorizedException.java
    - src/main/java/com/smartcoffee/common/exception/ForbiddenException.java
    - src/main/java/com/smartcoffee/common/exception/ConflictException.java
    - src/main/java/com/smartcoffee/common/exception/ValidationException.java
    - src/main/java/com/smartcoffee/common/exception/InfrastructureException.java
---

## System Overview

The Smart Coffee backend uses a Spring Boot-based error handling strategy centered on a single `GlobalExceptionHandler` (`@RestControllerAdvice`) that converts domain and framework exceptions into a uniform JSON error envelope. The approach combines typed business exceptions, Spring validation exceptions, and a catch-all for unexpected errors.

## Core Components

### Exception Hierarchy

All application-level errors extend a base `BusinessException` in `com.smartcoffee.common.exception`, which carries three fields: a machine-readable `code` (String), an HTTP `status` (int), and the message. Specialized subclasses encode standard HTTP semantics:

- `ResourceNotFoundException` — code `RESOURCE_NOT_FOUND`, status 404
- `UnauthorizedException` — code `AUTH_UNAUTHORIZED`, status 401
- `ForbiddenException` — code `AUTH_FORBIDDEN`, status 403
- `ConflictException` — code `CONFLICT`, status 409
- `ValidationException` — code `VALIDATION_ERROR`, status 400
- `InfrastructureException` — code `SERVICE_TEMPORARILY_UNAVAILABLE`, status 503

Each subclass exposes both a convenience constructor taking just a message (with a default code) and an overloaded constructor accepting a custom code string, allowing modules to define domain-specific codes while reusing the same HTTP mapping.

There is also a legacy duplicate `BusinessException` in `com.smartcoffee.common` that wraps Flyway's `ErrorCode` and Spring's `HttpStatus`; it is not used by the global handler and appears to be unused or legacy.

### Global Exception Handler

`GlobalExceptionHandler` maps exceptions to responses via `@ExceptionHandler` methods:

| Exception | HTTP Status | Response Code |
|---|---|---|
| `BusinessException` (and subclasses) | From exception `.status()` | From exception `.code()` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_ERROR` |
| `ConstraintViolationException` | 400 | `VALIDATION_ERROR` |
| `Exception` (catch-all) | 500 | `INTERNAL_ERROR` |

Validation errors from `MethodArgumentNotValidException` are expanded into per-field `ValidationError` records containing field name, code, and default message; constraint violations return a generic validation response without field details.

### Response Envelope

All error responses use the `ApiErrorResponse` record, which contains:
- `success: false`
- `error: { code, message, details }`
- `requestId` — pulled from `CorrelationContext.requestId()`
- `timestamp` — `Instant.now()`

This provides consistent correlation across requests and errors.

### Usage Pattern Across Modules

Domain services throw typed business exceptions rather than returning error objects or using result types. Examples observed:
- `CurrentActorProvider` throws `UnauthorizedException` when authentication is missing
- `PermissionEvaluator` throws `ForbiddenException` when a required permission is absent
- `StoreAuthorizationService` throws `ForbiddenException` with a domain-specific code
- `IdempotencyService` throws `ConflictException` for idempotency key reuse or in-flight requests

For internal preconditions (e.g., null arguments in infrastructure classes like `EventEnvelope`, `CanonicalRequestHasher`, `RaceSafeConsumerInboxService`), `IllegalArgumentException` and `IllegalStateException` are thrown directly — these are NOT caught by the global handler and will surface as 500 `INTERNAL_ERROR` responses via the catch-all.

## Architecture & Conventions

1. **Single source of truth for HTTP mapping**: All HTTP status-to-code mappings live in the exception class hierarchy, not in controllers or handlers. Adding a new error type means adding a subclass of `BusinessException`.
2. **Machine-readable error codes**: Every business error has a stable string code (e.g. `IDEMPOTENCY_KEY_REUSED`, `STORE_ACCESS_DENIED`) enabling client-side error routing and logging.
3. **Validation separation**: Bean validation failures go through Spring's built-in exceptions and are mapped uniformly; domain-level validation failures should throw `ValidationException` or a domain-specific subclass.
4. **No try/catch around service calls**: Controllers and services rely on exception propagation to the global handler rather than wrapping calls in try/catch blocks.
5. **Correlation IDs**: Every error response includes the request ID from `CorrelationContext`, enabling end-to-end tracing.
6. **Catch-all fallback**: Any unhandled `Exception` returns a generic `INTERNAL_ERROR` with no stack trace exposed to clients, preventing information leakage.

## Constraints Observed

- Domain logic must not return `null` or error indicators; it must throw typed exceptions.
- Infrastructure/internal validation uses `IllegalArgumentException`/`IllegalStateException` — these bypass the business exception path and produce 500 responses.
- There is no `@ControllerAdvice` at module boundaries; all cross-cutting error handling is centralized in `common.api.GlobalExceptionHandler`.
- No `@ResponseStatus` annotations are used on controller methods; HTTP status is derived exclusively from exception instances.