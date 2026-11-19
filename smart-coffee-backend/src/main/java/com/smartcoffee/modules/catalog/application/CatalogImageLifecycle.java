package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.infrastructure.storage.StorageException;
import com.smartcoffee.infrastructure.storage.StoragePathPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Orchestrates the MinIO object lifecycle that backs a product/category image
 * across the DB commit boundary. There is no distributed transaction between
 * PostgreSQL and the bucket, so the two supported transitions are handled by
 * application-level compensation:
 *
 * <ul>
 *   <li><b>Create</b> ({@link #compensateCreate}): if persisting the new row
 *       fails, the freshly uploaded object is deleted best-effort so it never
 *       becomes an orphan.</li>
 *   <li><b>Replace / clear</b> ({@link #deleteAfterCommit}): the previous object
 *       is deleted only AFTER the surrounding transaction commits successfully,
 *       via a {@link TransactionSynchronization} {@code afterCommit} callback. A
 *       rollback therefore never deletes live storage; a delete that fails after
 *       commit is logged, not rolled back (leaving at worst a reclaimable orphan
 *       rather than a dangling reference).</li>
 * </ul>
 *
 * <p>{@link #validate} runs the object-key policy for writes: blank/null is
 * allowed (no image / clear image), otherwise the key must be safe
 * ({@link StoragePathPolicy#requireAllowedKey}) AND belong to the owning
 * category's prefix, so a product can never reference a {@code users/},
 * {@code receipts/} or another area's object.
 */
@Component
public class CatalogImageLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CatalogImageLifecycle.class);

    private final ObjectStorageService storage;
    private final StoragePathPolicy pathPolicy;

    public CatalogImageLifecycle(ObjectStorageService storage, StoragePathPolicy pathPolicy) {
        this.storage = storage;
        this.pathPolicy = pathPolicy;
    }

    /**
     * Validates a client-supplied image object key for the given owning prefix.
     *
     * @return the trimmed key, or {@code null} when blank/absent (no image).
     * @throws StorageException if the key is unsafe or outside {@code requiredPrefix}.
     */
    public String validate(String objectKey, String requiredPrefix) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        String trimmed = objectKey.trim();
        pathPolicy.requireAllowedKey(trimmed);
        if (!trimmed.startsWith(requiredPrefix)) {
            throw StorageException.invalidObjectKey(
                    "Image object key must belong to the '" + requiredPrefix + "' area");
        }
        return trimmed;
    }

    /** Best-effort removal of a just-uploaded object when its create persists fails. */
    public void compensateCreate(String newKey) {
        if (newKey == null || newKey.isBlank()) {
            return;
        }
        safeDelete(newKey, "create-compensation");
    }

    /**
     * Schedules deletion of {@code oldKey} for AFTER the current transaction
     * commits. No-op when {@code oldKey} is blank or equals {@code newKey} (the
     * image did not actually change). Falls back to an immediate best-effort
     * delete when no transaction synchronization is active.
     */
    public void deleteAfterCommit(String oldKey, String newKey) {
        if (oldKey == null || oldKey.isBlank() || oldKey.equals(newKey)) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeDelete(oldKey, "no-transaction");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeDelete(oldKey, "post-commit");
            }
        });
    }

    private void safeDelete(String key, String phase) {
        try {
            storage.delete(key);
        } catch (RuntimeException ex) {
            // Never fail the business operation on storage cleanup: a leftover
            // object is reclaimable, a thrown error would corrupt the write.
            log.warn("catalog_image_cleanup_failed phase={} key={} error={}", phase, key, ex.toString());
        }
    }
}
