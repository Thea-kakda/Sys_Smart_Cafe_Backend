package com.smartcoffee.testsupport;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Test-only JDBC statement counter. Hibernate instantiates it from the
 * {@code hibernate.session_factory.statement_inspector} property; the counter is
 * thread-local so background consumers in the shared test JVM cannot inflate a
 * test's query count.
 */
public class SqlCounter implements StatementInspector {

    private static final ThreadLocal<Integer> COUNT = ThreadLocal.withInitial(() -> 0);

    public SqlCounter() {
    }

    @Override
    public String inspect(String sql) {
        COUNT.set(COUNT.get() + 1);
        return sql;
    }

    public static void reset() {
        COUNT.set(0);
    }

    public static int count() {
        return COUNT.get();
    }
}
