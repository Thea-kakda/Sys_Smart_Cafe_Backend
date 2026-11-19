package com.smartcoffee.infrastructure.retry;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded retry for recognized TRANSIENT database failures only (Slice 2E,
 * spec §15). There is no spring-retry on the classpath, so the policy is small,
 * explicit and — crucially — the retry loop lives ABOVE the transaction boundary:
 * every attempt runs inside its own {@code REQUIRES_NEW} transaction via
 * {@link TransactionTemplate}, so a rolled-back attempt leaves nothing behind and
 * the caller's idempotency identity is simply re-presented to a fresh transaction.
 *
 * <p>Retried: SQLState {@code 40001} (serialization failure), {@code 40P01}
 * (deadlock detected), and Spring's {@link ObjectOptimisticLockingFailureException}
 * where the operation is written to be replay-safe. Never retried: business
 * conflicts (INSUFFICIENT_STOCK / RESERVATION_NOT_ACTIVE / ORDER_STATE_CONFLICT),
 * validation errors, permission failures or idempotency-key misuse — those are
 * rethrown untouched on the first occurrence.
 *
 * <p>Metrics ({@code reservation.retry} / {@code reservation.retry_exhausted})
 * carry only a fixed {@code operation} tag; identifiers go to the structured log.
 */
@Component
public class TransientRetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransientRetryExecutor.class);
    private static final String METRIC_RETRY = "reservation.retry";
    private static final String METRIC_RETRY_EXHAUSTED = "reservation.retry_exhausted";

    private final TransactionTemplate requiresNew;
    private final MeterRegistry registry;
    private final int maxAttempts;
    private final long baseBackoffMs;
    // Seam for tests: production sleeps on the thread; unit tests inject a no-op
    // so retry behavior is verified without Thread.sleep.
    private LongConsumerSleeper sleeper = ms -> {
        try {
            if (ms > 0) {
                Thread.sleep(ms);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    public TransientRetryExecutor(PlatformTransactionManager txManager, MeterRegistry registry,
            @Value("${app.retry.max-attempts:3}") int maxAttempts,
            @Value("${app.retry.base-backoff-ms:20}") long baseBackoffMs) {
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.registry = registry;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMs = Math.max(0, baseBackoffMs);
    }

    /**
     * Runs {@code work} inside its own new transaction, retrying only transient
     * DB failures up to {@code maxAttempts}. The same {@code work} (and therefore
     * the same idempotency identity) is re-invoked on each attempt.
     */
    public <T> T execute(String operation, Supplier<T> work) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return requiresNew.execute(status -> work.get());
            } catch (RuntimeException ex) {
                if (!isTransient(ex)) {
                    throw ex; // business/validation/permission: never retried
                }
                if (attempt >= maxAttempts) {
                    registry.counter(METRIC_RETRY_EXHAUSTED, "operation", operation).increment();
                    log.warn("transient db failure exhausted operation={} attempts={} error={}", operation, attempt,
                            ex.toString());
                    throw ex;
                }
                registry.counter(METRIC_RETRY, "operation", operation).increment();
                long backoff = backoffWithJitter(attempt);
                log.warn("transient db failure, retrying operation={} attempt={} backoffMs={} error={}", operation,
                        attempt, backoff, ex.toString());
                sleeper.sleep(backoff);
            }
        }
    }

    /** Exposed for the retry-policy unit test (classification without a DB). */
    public static boolean isTransient(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ObjectOptimisticLockingFailureException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("40001".equals(state) || "40P01".equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Small bounded exponential backoff with jitter; capped so a batch stays prompt. */
    long backoffWithJitter(int attempt) {
        long exp = baseBackoffMs * (1L << Math.min(attempt - 1, 4));
        long capped = Math.min(exp, 500L);
        return capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
    }

    void setSleeperForTest(LongConsumerSleeper sleeper) {
        this.sleeper = sleeper;
    }

    /** Test seam so retry logic can be exercised without sleeping. */
    @FunctionalInterface
    public interface LongConsumerSleeper {
        void sleep(long millis);
    }
}
