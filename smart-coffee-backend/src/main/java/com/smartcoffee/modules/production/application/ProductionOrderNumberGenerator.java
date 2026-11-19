package com.smartcoffee.modules.production.application;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side generator for human-readable Production Order numbers (Slice 5A
 * spec §13). Uniqueness is guaranteed by a Postgres sequence — never an
 * in-memory counter — so concurrent creates cannot collide and a restart of
 * the JVM never rewinds the numbering. The date component is informational;
 * the sequence value is what actually de-duplicates.
 *
 * <p>Format: {@code PO-yyyyMMdd-<zero-padded sequence>}. Example: {@code
 * PO-20261106-000123}. The padding is 6 digits which supports up to 999 999
 * POs before the string grows; nothing depends on the padded width being
 * fixed after overflow.
 *
 * <p>Uses {@link Propagation#REQUIRES_NEW} so the sequence {@code nextval}
 * does not get rolled back with the surrounding {@code createDraft}
 * transaction if a downstream constraint fails. A gap in the numbering is a
 * feature, not a bug: it makes concurrent failed retries transparent to the
 * shop-floor log rather than reusing the same number for two attempts.
 */
@Service
public class ProductionOrderNumberGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;

    public ProductionOrderNumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        Long value = jdbc.queryForObject("select nextval('production.production_order_number_seq')", Long.class);
        long seq = value == null ? 1L : value;
        String date = LocalDate.now(ZoneOffset.UTC).format(DATE);
        return String.format("PO-%s-%06d", date, seq);
    }
}
