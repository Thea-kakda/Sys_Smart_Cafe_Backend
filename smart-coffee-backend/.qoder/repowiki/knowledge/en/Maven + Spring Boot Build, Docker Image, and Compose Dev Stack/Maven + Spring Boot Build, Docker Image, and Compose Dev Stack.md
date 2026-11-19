---
kind: build_system
name: Maven + Spring Boot Build, Docker Image, and Compose Dev Stack
category: build_system
scope:
    - '**'
source_files:
    - pom.xml
    - Dockerfile
    - compose.yaml
    - .mvn/wrapper/maven-wrapper.properties
    - src/main/resources/db/migration/V001__identity_auth_rbac.sql
    - src/main/resources/db/migration/V012__fix_outbox_claiming.sql
    - src/test/resources/application-test.yml
---

# Build System — Smart Coffee Backend

## Approach

The project is a single-module Spring Boot application built with **Apache Maven** (wrapper 3.9.16) against the `spring-boot-starter-parent` BOM (Spring Boot 4.1.1). There is no CI pipeline in this repository (no `.github/` directory), no Makefile, and no custom shell scripts: build, packaging, and local orchestration are handled entirely by Maven, a multi-stage Dockerfile, and a Docker Compose file.

## Key Files

- `pom.xml` — project descriptor, dependency management, plugin configuration
- `Dockerfile` — two-stage image build
- `compose.yaml` — local dev stack (Postgres, Redis, RabbitMQ, MinIO)
- `.mvn/wrapper/maven-wrapper.properties` — pinned Maven wrapper version and distribution URL
- `src/main/resources/db/migration/*.sql` — Flyway migration files (`V001__...` through `V012__...`)
- `src/test/resources/application-test.yml` — test profile config
- `src/main/resources/application.yml` — runtime config consumed by Spring Boot

## Build Lifecycle & Plugins

### Compilation

- Java source/target level: `java.version=26` (declared in `<properties>`).
- The `maven-compiler-plugin` is configured with `<parameters>true</parameters>` so method parameter names are retained for reflection (used by Spring validation / Jackson).
- No explicit `<source>/<target>` override — inherited from Spring Boot parent.

### Packaging

- `spring-boot-maven-plugin` is bound via the parent; produces an executable fat JAR under `target/`.
- The Dockerfile's build stage runs `mvn -q -DskipTests package`, producing the same artifact without running unit or integration tests.

### Testing

Two test scopes are wired:

| Scope | Plugin / Convention | Pattern |
|---|---|---|
| Unit tests | `spring-boot-starter-test` (JUnit 5) | `src/test/java/**/*Test.java` (default Surefire behavior) |
| Integration tests | `maven-failsafe-plugin` v3.5.5 | `src/test/java/**/*IT.java` (explicitly included via `<includes>`) |

Failsafe goals `integration-test` and `verify` are executed, so integration tests run during the normal `mvn verify` lifecycle.

### Dependency Management

- Centralized via `spring-boot-starter-parent` plus two imported BOMs in `<dependencyManagement>`:
  - `org.springframework.ai:spring-ai-bom:${spring-ai.version}` (2.0.1)
  - `de.codecentric:spring-boot-admin-dependencies:${spring-boot-admin.version}` (4.1.1)
- Third-party versions declared as properties: `spring-ai.version`, `springdoc.version` (3.1.0), `bouncycastle.version` (1.82), `minio.version` (8.5.17).
- Optional/runtime-only dependencies: `spring-boot-devtools`, `spring-boot-docker-compose`, `spring-ai-spring-boot-docker-compose`, `micrometer-registry-prometheus`, PostgreSQL driver.

### Versioning

- Project version: `0.0.1-SNAPSHOT` (standard Spring Boot default).
- No release or changelog automation is present in the POM.

## Containerization

The `Dockerfile` uses a **two-stage build**:

1. **Build stage** (`maven:3.9-eclipse-temurin-17`): copies `pom.xml` and `src/`, then runs `mvn -q -DskipTests package`.
2. **Runtime stage** (`eclipse-temurin:17-jre`): copies only the produced `*.jar` into `/app/app.jar` and sets `ENTRYPOINT ["java","-jar","/app/app.jar"]`.

The container exposes port `8080`. Note that the build stage uses JDK 17 while the POM declares `java.version=26`; the Maven wrapper pins Maven 3.9.16 but does not pin the JDK used by Maven itself.

## Local Development Stack

`compose.yaml` defines four services:

| Service | Image | Ports | Healthcheck |
|---|---|---|---|
| `postgres` | `postgres:18` | `5433:5432` | `pg_isready` |
| `redis` | `redis:7.4-alpine` | `6379:6379` | `redis-cli ping` |
| `rabbitmq` | `rabbitmq:4-management` | `5672`, `15672` | `rabbitmq-diagnostics -q ping` |
| `minio` | `minio/minio:latest` | `9000`, `9001` | none |

Redis and RabbitMQ use healthchecks with 5–10 s intervals and 20 retries. All services use `restart: unless-stopped`. Redis is labeled `org.springframework.boot.service-connection=redis` so Spring Boot can auto-connect to it. MinIO has no healthcheck.

## Database Migrations

Flyway is enabled via `spring-boot-starter-flyway` and `flyway-database-postgresql`. Migrations live under `src/main/resources/db/migration/` and follow the convention `VNNN__description.sql` (V001 through V012). A `README.md` exists alongside them.

## Conventions Observed

- Test classes ending in `*IT.java` are treated as integration tests and executed by Failsafe; all other tests are unit tests.
- Integration tests rely on Testcontainers (`testcontainers-postgresql`, `testcontainers-rabbitmq`, `testcontainers-junit-jupiter`) rather than external services.
- The Docker build skips tests (`-DskipTests`); any test execution must be invoked explicitly outside the Dockerfile.
- The compose stack pins major versions of Postgres (18), Redis (7.4), and RabbitMQ (4), but uses `minio/minio:latest` for MinIO.
- The Maven wrapper is committed (`.mvn/wrapper/maven-wrapper.properties`), fixing the Maven binary at 3.9.16.
- There is no CI configuration in this repository; build and deployment steps are not automated here.