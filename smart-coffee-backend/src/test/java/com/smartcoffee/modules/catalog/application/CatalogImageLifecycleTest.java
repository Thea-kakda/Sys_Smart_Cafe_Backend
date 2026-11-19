package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.infrastructure.storage.StorageException;
import com.smartcoffee.infrastructure.storage.StoragePathPolicy;
import com.smartcoffee.infrastructure.storage.StorageProperties;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit coverage for the image-lifecycle compensation rules without Docker:
 * object-key prefix ownership on writes, create-failure orphan cleanup, and the
 * post-commit (never pre-commit) deletion of a replaced object. A real
 * {@link StoragePathPolicy} is used so the shipped validation is exercised; only
 * the MinIO-backed {@link ObjectStorageService} is stubbed.
 */
@SuppressWarnings("null")
class CatalogImageLifecycleTest {

    private static final List<String> PREFIXES = List.of(
            "products/", "categories/", "users/", "invoices/", "receipts/", "documents/");

    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final CatalogImageLifecycle lifecycle =
            new CatalogImageLifecycle(storage, new StoragePathPolicy(new StorageProperties(
                    PREFIXES, List.of("image/jpeg"), 10_485_760L, 900L)));

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void validateAllowsBlankAndReturnsNull() {
        assertNull(lifecycle.validate(null, "products/"));
        assertNull(lifecycle.validate("   ", "products/"));
    }

    @Test
    void validateReturnsTrimmedKeyForOwningPrefix() {
        assertEquals("products/uuid-latte.jpg", lifecycle.validate("  products/uuid-latte.jpg  ", "products/"));
    }

    @Test
    void validateRejectsWrongCategoryPrefix() {
        StorageException ex = assertThrows(StorageException.class,
                () -> lifecycle.validate("users/uuid-avatar.jpg", "products/"));
        assertEquals("INVALID_OBJECT_KEY", ex.code());
    }

    @Test
    void validateRejectsPathTraversal() {
        assertThrows(StorageException.class,
                () -> lifecycle.validate("products/../../etc/passwd", "products/"));
    }

    @Test
    void compensateCreateDeletesObject() {
        lifecycle.compensateCreate("products/uuid-latte.jpg");
        verify(storage).delete("products/uuid-latte.jpg");
    }

    @Test
    void compensateCreateIgnoresBlankAndSwallowsStorageFailure() {
        lifecycle.compensateCreate("   ");
        verify(storage, never()).delete(any());

        doThrow(new RuntimeException("minio down")).when(storage).delete("products/x.jpg");
        // A cleanup failure must never mask the original business error.
        lifecycle.compensateCreate("products/x.jpg");
        verify(storage).delete("products/x.jpg");
    }

    @Test
    void deleteAfterCommitNoopsWhenUnchangedOrBlank() {
        lifecycle.deleteAfterCommit(null, "products/new.jpg");
        lifecycle.deleteAfterCommit("", "products/new.jpg");
        lifecycle.deleteAfterCommit("products/same.jpg", "products/same.jpg");
        verify(storage, never()).delete(any());
    }

    @Test
    void deleteAfterCommitRunsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        lifecycle.deleteAfterCommit("products/old.jpg", "products/new.jpg");

        // Deferred: nothing deleted while the transaction is still in flight.
        verify(storage, never()).delete(any());

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());
        syncs.forEach(TransactionSynchronization::afterCommit);

        verify(storage, times(1)).delete("products/old.jpg");
    }

    @Test
    void rollbackNeverDeletesLiveObject() {
        TransactionSynchronizationManager.initSynchronization();
        lifecycle.deleteAfterCommit("products/old.jpg", "products/new.jpg");
        // A rollback does not invoke afterCommit, so the old object survives.
        verify(storage, never()).delete(any());
    }
}
