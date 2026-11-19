package com.smartcoffee.testsupport;

import org.junit.jupiter.api.Tag;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
public abstract class IntegrationTestBase {

    /*
     * Singleton containers (Testcontainers recommended pattern): started once
     * per JVM via the static initializer below and deliberately NOT annotated
     * @Container. With @Container on inherited static fields, the JUnit 5
     * extension stops and restarts the containers after each test class while
     * Spring's context cache keeps reusing the first ApplicationContext with
     * the first port bindings baked into the DataSource, so every IT class
     * after the first died with "Connection refused" on stale dynamic ports.
     */
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse(
                            "postgres:17-alpine"))
                    .withStartupTimeout(Duration.ofSeconds(180));

    @SuppressWarnings("resource")
    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(
                    DockerImageName.parse(
                            "rabbitmq:4-management"))
                    .withStartupTimeout(Duration.ofSeconds(180));

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                    DockerImageName.parse(
                            "redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .withStartupTimeout(Duration.ofSeconds(180));

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> MINIO =
            new GenericContainer<>(
                    DockerImageName.parse(
                            "minio/minio:latest"))
                    .withCommand("server /data")
                    .withEnv(
                            "MINIO_ROOT_USER",
                            "minioadmin")
                    .withEnv(
                            "MINIO_ROOT_PASSWORD",
                            "minioadmin")
                    .withExposedPorts(9000)
                    .withStartupTimeout(Duration.ofSeconds(180));

    static {
        // Guard mirrors @Testcontainers(disabledWithoutDocker): when Docker is
        // absent the extension skips the tests and no container is started.
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            RABBIT.start();
            REDIS.start();
            MINIO.start();
        }
    }

    @DynamicPropertySource
    static void properties(
            DynamicPropertyRegistry registry) {

        registry.add(
                "spring.datasource.url",
                POSTGRES::getJdbcUrl);

        registry.add(
                "spring.datasource.username",
                POSTGRES::getUsername);

        registry.add(
                "spring.datasource.password",
                POSTGRES::getPassword);

        registry.add(
                "spring.rabbitmq.host",
                RABBIT::getHost);

        registry.add(
                "spring.rabbitmq.port",
                RABBIT::getAmqpPort);

        registry.add(
                "spring.data.redis.host",
                REDIS::getHost);

        registry.add(
                "spring.data.redis.port",
                () -> REDIS.getMappedPort(6379));

        registry.add(
                "app.minio.endpoint",
                () -> "http://"
                        + MINIO.getHost()
                        + ":"
                        + MINIO.getMappedPort(9000));

        registry.add(
                "app.minio.access-key",
                () -> "minioadmin");

        registry.add(
                "app.minio.secret-key",
                () -> "minioadmin");
    }
}
