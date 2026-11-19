package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.inventory.api.StockTransferResponse;
import com.smartcoffee.modules.inventory.persistence.StockTransferRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock-transfer reads (paginated list per store as source or destination,
 * single detail), guarded by {@code inventory:transfer} plus the store scope.
 */
@Service
public class StockTransferQueryService {

    private final StockTransferRepository transfers;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;
    private final StockTransferMapper mapper;

    public StockTransferQueryService(StockTransferRepository transfers, PermissionEvaluator permissions,
            StoreAuthorizationService stores, StockTransferMapper mapper) {
        this.transfers = transfers;
        this.permissions = permissions;
        this.stores = stores;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page<StockTransferResponse> list(CurrentActor actor, UUID storeId, int page, int size) {
        permissions.require(actor, PermissionCodes.INVENTORY_TRANSFER);
        stores.requireStore(actor, storeId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return transfers.byStore(storeId, PageRequest.of(safePage, safeSize)).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public StockTransferResponse get(CurrentActor actor, UUID transferId) {
        permissions.require(actor, PermissionCodes.INVENTORY_TRANSFER);
        return transfers.findWithLines(transferId).map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("STOCK_TRANSFER_NOT_FOUND", "Stock transfer not found"));
    }
}
