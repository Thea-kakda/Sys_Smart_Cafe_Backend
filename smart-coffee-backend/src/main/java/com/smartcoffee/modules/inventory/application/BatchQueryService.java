package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.inventory.api.BatchExpiryResponse;
import com.smartcoffee.modules.inventory.persistence.BatchQueryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only batch / expiry listing (V038, M3). Guarded by {@code inventory:read}
 * plus the store scope. Lists a store's lots FEFO-ordered, optionally narrowed
 * to one ingredient or to those expiring within a day window. Nothing here
 * mutates stock — retiring a lot is the explicit {@link BatchService#writeOff}
 * path.
 */
@Service
public class BatchQueryService {

    private final BatchQueryRepository batchReads;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public BatchQueryService(BatchQueryRepository batchReads, PermissionEvaluator permissions,
            StoreAuthorizationService stores) {
        this.batchReads = batchReads;
        this.permissions = permissions;
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public List<BatchExpiryResponse> batches(CurrentActor actor, UUID storeId, UUID ingredientId, Integer expiringDays) {
        permissions.require(actor, PermissionCodes.INVENTORY_READ);
        stores.requireStore(actor, storeId);
        boolean ingredientFilter = ingredientId != null;
        boolean expiringFilter = expiringDays != null && expiringDays >= 0;
        int expiringWindow = (expiringDays != null && expiringDays >= 0) ? expiringDays : 0;
        return batchReads.batches(storeId, ingredientFilter, ingredientFilter ? ingredientId : new UUID(0, 0),
                expiringFilter, expiringWindow).stream()
                .map(BatchMapper::toResponse).toList();
    }
}
