---
kind: dependency_management
name: Maven + Spring Boot Parent Dependency Management
category: dependency_management
scope:
    - '**'
source_files:
    - pom.xml
    - .mvn/wrapper/maven-wrapper.properties
    - Dockerfile
    - compose.yaml
---

## System / Approach

The repository is a single Maven project (`pom.xml`, `groupId=com.smartcoffee`, `artifactId=identity-service`) built with **Apache Maven** and the **Spring Boot Maven plugin**. There are no Gradle, Node/npm (for Java), or vendored third-party JARs — all dependencies are resolved from Maven Central via the Maven Wrapper.

### Toolchain
- **Maven**: pinned to `3.9.16` via `.mvn/wrapper/maven-wrapper.properties` (`wrapperVersion=3.3.4`, `distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip`).
- **Java**: `<java.version>26</java.version>` declared in POM properties; the Dockerfile uses `eclipse-temurin:17-jre` for runtime and `maven:3.9-eclipse-temurin-17` for build, so there is a mismatch between the declared source/target version and the container image.
- **Build tooling**: `spring-boot-maven-plugin` (from parent), `maven-compiler-plugin` with `<parameters>true</parameters>`, and `maven-failsafe-plugin` (version `3.5.5`) bound to `integration-test`/`verify` goals for `**/*IT.java` integration tests.

## Key Files

- `pom.xml` — sole dependency manifest. Declares the Spring Boot parent, all `<dependencies>`, a `<dependencyManagement>` block importing two BOMs, and the `<build>` plugins.
- `.mvn/wrapper/maven-wrapper.properties` — pins Maven distribution and wrapper version.
- `Dockerfile` — multi-stage build that runs `mvn -q -DskipTests package` then ships the resulting JAR on a JRE-only base image.
- `compose.yaml` — declares local infrastructure services (PostgreSQL 18, Redis 7.4-alpine, RabbitMQ 4-management, MinIO) used by the app at dev/test time.

## Architecture & Conventions

### 1. Spring Boot Parent as the single source of truth
The project inherits from `org.springframework.boot:spring-boot-starter-parent:4.1.1`. This means:
- All Spring-managed starters (`spring-boot-starter-web`, `-data-jpa`, `-security`, `-amqp`, `-actuator`, etc.) inherit their versions from the parent's managed versions — no explicit `<version>` tags on those artifacts.
- The parent also configures default plugins (compiler, surefire/failsafe, resource filtering).

### 2. Explicitly versioned non-Spring libraries
Libraries outside the Spring ecosystem declare their own `<version>`:
- `io.minio:minio:${minio.version}` where `${minio.version}=8.5.17`
- `org.bouncycastle:bcprov-jdk18on:${bouncycastle.version}` where `${bouncycastle.version}=1.82`
- `org.springdoc:springdoc-openapi-starter-webmvc-ui:${springdoc.version}` where `${springdoc.version}=3.1.0`
- `org.testcontainers:*` test-scoped dependencies (no version pinning — inherited from Spring Boot parent)

All such versions are centralized under the `<properties>` block at the top of `pom.xml`.

### 3. BOM imports for cross-cutting ecosystems
A `<dependencyManagement>` block imports two external Bill-of-Materials:
- `org.springframework.ai:spring-ai-bom` at `${spring-ai.version}=2.0.1` (controls `spring-ai-spring-boot-docker-compose`)
- `de.codecentric:spring-boot-admin-dependencies` at `${spring-boot-admin.version}=4.1.1` (controls `spring-boot-admin-starter-server`)

This is the only place in the POM where `<dependencyManagement>` is used; it does not re-declare every dependency's version — only these two BOMs are imported.

### 4. Scope discipline
- Runtime-only dependencies use `<scope>runtime</scope>`: PostgreSQL driver, Prometheus Micrometer registry, Spring AI docker-compose helper, Spring Boot docker-compose helper, devtools.
- Test-only dependencies use `<scope>test</scope>`: Spring Boot Test, Spring Security Test, Testcontainers suite, PostgreSQL/RabbitMQ containers.
- Some optional dev/runtime helpers are marked `<optional>true</optional>` alongside `runtime` scope (Spring AI docker-compose, Spring Boot docker-compose, devtools).

### 5. No private registry, no vendoring
There is no custom `<repositories>` section in `pom.xml`, no `.m2/settings.xml` overrides visible in the tree, no `vendor/` directory, and no GOPRIVATE-style configuration. All artifacts resolve from Maven Central through the Maven Wrapper.

### 6. Infrastructure-as-code for runtime deps
External services (Postgres, Redis, RabbitMQ, MinIO) are declared as Docker Compose services in `compose.yaml` rather than pulled as application dependencies, keeping the runtime surface minimal.

## Conventions & Constraints

- **Single-module Maven project** — all code lives under `src/main/java/com/smartcoffee/`; there are no child modules, so dependency management is flat.
- **Versions live in `<properties>`** — any non-Starter library version should be added as a property (see `minio.version`, `bouncycastle.version`, `springdoc.version`, `spring-ai.version`, `spring-boot-admin.version`) rather than duplicated inline.
- **Use Spring Boot Starters** — web, validation, websocket, data-jpa, security, oauth2-resource-server, amqp, actuator, cache, data-redis, flyway, test, devtools are all consumed via `spring-boot-starter-*` artifacts, relying on the parent for version alignment.
- **BOMs for non-Boot ecosystems** — when pulling libraries outside Spring Boot's managed set (Spring AI, Spring Boot Admin), import their BOM in `<dependencyManagement>` instead of pinning each artifact's version individually.
- **Scope is explicit** — production vs. test vs. runtime-only vs. optional is consistently declared per dependency.
- **Maven Wrapper is committed** — builds must go through `./mvnw` (or `mvnw.cmd`); the exact Maven distribution (`3.9.16`) is reproducible via the wrapper.
- **No lockfile equivalent** — Maven does not produce a checked-in lockfile; transitive versions come from the parent POM and BOMs. There is no `*.lock` file for Java dependencies.
- **Dockerfile mismatch** — the POM declares `<java.version>26</java.version>` but the Dockerfile base images use Temurin 17. This is an observable inconsistency between the declared language level and the container runtime.
- **Integration tests isolated via Failsafe** — `maven-failsafe-plugin` is configured to run only `**/*IT.java` classes during the `integration-test`/`verify` lifecycle, separating unit and integration test scopes.