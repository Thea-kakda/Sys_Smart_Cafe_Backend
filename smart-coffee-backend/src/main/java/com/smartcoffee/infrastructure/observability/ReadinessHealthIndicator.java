package com.smartcoffee.infrastructure.observability;


import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("databaseReadiness")
public class ReadinessHealthIndicator implements HealthIndicator {
    private final JdbcTemplate jdbc;

    public ReadinessHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }


    @Override
    public Health health() {
        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            return one != null && one == 1 ? Health.up().build() : Health.down().build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}

