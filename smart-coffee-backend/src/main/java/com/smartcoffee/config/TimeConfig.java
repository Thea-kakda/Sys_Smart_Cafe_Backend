package com.smartcoffee.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single application-wide {@link Clock} (Slice 2E). Recovery, expiration and
 * reconciliation comparisons read "now" through this bean instead of calling
 * {@code Instant.now()} directly, so:
 *
 * <ul>
 *   <li>the scheduler and the domain guards interpret time identically (one
 *       inclusive {@code expiresAt &lt;= now} rule), and</li>
 *   <li>tests pin a {@code Clock.fixed(...)} and never sleep.</li>
 * </ul>
 *
 * <p>UTC, matching {@code spring.jpa.properties.hibernate.jdbc.time_zone=UTC}
 * and the {@code timestamptz} columns. Historical timestamp semantics are
 * unchanged — this only gives the new recovery paths a controllable time source.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
