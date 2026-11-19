package com.smartcoffee.infrastructure.retry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Slice 2E bounded-retry tests (spec §15/§27 item 8). The loop lives above the
 * transaction boundary, so a mocked {@link PlatformTransactionManager} is enough to
 * drive a real {@code TransactionTemplate} — the point is the RETRY POLICY, not DB
 * behaviour. Retried: transient SQLState 40001/40P01 and optimistic-lock failures.
 * Never retried: business/validation/permission failures. The attempt ceiling is
 * enforced and each attempt re-presents the SAME unit of work (idempotency identity).
 */
class TransientRetryExecutorTest {

    private SimpleMeterRegistry registry;
    private TransientRetryExecutor executor;

    @BeforeEach
    void setup() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        registry = new SimpleMeterRegistry();
        executor = new TransientRetryExecutor(txManager, registry, 3, 20);
        executor.setSleeperForTest(ms -> {
        }); // no Thread.sleep (spec §16)
    }

    // ------------------------------------------------------------- classification

    @Test
    void deadlockAndSerializationAndOptimisticAreTransient() {
        assertTrue(TransientRetryExecutor.isTransient(dbError("40001")), "serialization failure");
        assertTrue(TransientRetryExecutor.isTransient(dbError("40P01")), "deadlock detected");
        assertTrue(TransientRetryExecutor.isTransient(new ObjectOptimisticLockingFailureException("Entity", 1)));
    }

    @Test
    void otherSqlStatesAndBusinessFailuresAreNotTransient() {
        assertFalse(TransientRetryExecutor.isTransient(dbError("23505")), "unique violation is not transient");
        assertFalse(TransientRetryExecutor.isTransient(new ConflictException("INSUFFICIENT_STOCK", "no stock")));
        assertFalse(TransientRetryExecutor.isTransient(new ForbiddenException("no permission")));
        assertFalse(TransientRetryExecutor.isTransient(new IllegalStateException("boom")));
    }

    // ---------------------------------------------------------------- retry loop

    @Test
    void transientFailureRetriesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = executor.execute("op", () -> {
            if (calls.incrementAndGet() < 3) {
                throw dbError("40P01");
            }
            return "done";
        });

        assertEquals("done", result);
        assertEquals(3, calls.get(), "two transient failures then a success");
        assertEquals(2.0, registry.get("reservation.retry").tag("operation", "op").counter().count());
    }

    @Test
    void retryIsBoundedAndExhaustionIsRecorded() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> executor.execute("op", () -> {
            calls.incrementAndGet();
            throw dbError("40001");
        }));

        assertEquals(3, calls.get(), "max-attempts ceiling is enforced, not unbounded");
        assertEquals(1.0, registry.get("reservation.retry_exhausted").tag("operation", "op").counter().count());
    }

    @Test
    void businessConflictIsNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(ConflictException.class, () -> executor.execute("op", () -> {
            calls.incrementAndGet();
            throw new ConflictException("RESERVATION_NOT_ACTIVE", "gone");
        }));

        assertEquals(1, calls.get());
        assertEquals(0.0, registry.find("reservation.retry").counter() == null ? 0.0
                : registry.find("reservation.retry").counter().count(), "no retry on business conflict");
    }

    @Test
    void permissionFailureIsNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(ForbiddenException.class, () -> executor.execute("op", () -> {
            calls.incrementAndGet();
            throw new ForbiddenException("STORE_ACCESS_DENIED", "denied");
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void sameIdempotencyIdentityIsRePresentedOnEveryAttempt() {
        // The identical Supplier (and thus the same captured key) runs on each attempt;
        // a transient rollback leaves nothing behind, so re-presenting is safe.
        String key = "IDEM-42";
        AtomicInteger calls = new AtomicInteger();
        java.util.List<String> seenKeys = new java.util.concurrent.CopyOnWriteArrayList<>();

        executor.execute("release", () -> {
            seenKeys.add(key);
            if (calls.incrementAndGet() < 2) {
                throw dbError("40001");
            }
            return key;
        });

        assertEquals(2, seenKeys.size());
        assertTrue(seenKeys.stream().allMatch(k -> k.equals("IDEM-42")),
                "every attempt re-presents the same idempotency identity");
    }

    // -------------------------------------------------------------------- helpers

    private static RuntimeException dbError(String sqlState) {
        return new RuntimeException("outer", new SQLException("db failure", sqlState));
    }
}
