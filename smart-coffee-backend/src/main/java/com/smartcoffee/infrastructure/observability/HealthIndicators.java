package com.smartcoffee.infrastructure.observability;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class HealthIndicators implements HealthIndicator {

    @Override
    public Health health() {
        return Health.up()
                .withDetail("service", "smart-coffee-api")
                .build();
    }
}
