package com.smartcoffee.identity;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(
                DockerImageName.parse("postgres:17-alpine")
        );
    }

    @Bean
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    GenericContainer<?> redisStackContainer() {
        return new GenericContainer<>(
                DockerImageName.parse("redis/redis-stack:latest")
        ).withExposedPorts(6379);
    }
}
