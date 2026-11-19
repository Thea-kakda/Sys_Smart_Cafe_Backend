package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.inventory.api.PurchaseOrderResponse;
import com.smartcoffee.modules.inventory.domain.PurchaseOrderStatus;
import com.smartcoffee.modules.inventory.persistence.PurchaseOrderRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase-order reads (paginated list per store with an optional status filter,
 * single detail), guarded by {@code purchase:manage}.
 */
@Service
public class PurchaseOrderQueryService {

    private final PurchaseOrderRepository orders;
    private final PermissionEvaluator permissions;
    private final PurchaseOrderMapper mapper;

    public PurchaseOrderQueryService(PurchaseOrderRepository orders, PermissionEvaluator permissions,
            PurchaseOrderMapper mapper) {
        this.orders = orders;
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrderResponse> list(CurrentActor actor, UUID storeId, String status, int page, int size) {
        permissions.require(actor, PermissionCodes.PURCHASE_MANAGE);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        boolean filter = status != null && !status.isBlank();
        List<PurchaseOrderStatus> statuses = filter ? List.of(parseStatus(status)) : List.of();
        return orders.byStore(storeId, filter, statuses, PageRequest.of(safePage, safeSize))
                .map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(CurrentActor actor, UUID storeId, UUID poId) {
        permissions.require(actor, PermissionCodes.PURCHASE_MANAGE);
        return orders.findWithLines(poId)
                .filter(po -> po.storeId().equals(storeId))
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("PURCHASE_ORDER_NOT_FOUND", "Purchase order not found"));
    }

    private PurchaseOrderStatus parseStatus(String raw) {
        try {
            return PurchaseOrderStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown purchase order status: " + raw);
        }
    }
}
