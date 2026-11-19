package com.smartcoffee.modules.production.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.production.api.ProductionOrderResponse;
import com.smartcoffee.modules.production.domain.ProductionOrder;
import com.smartcoffee.modules.production.domain.ProductionOrderStatus;
import com.smartcoffee.modules.production.persistence.ProductionOrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the Production Order aggregate (Slice 5A §21). Store scoping
 * is enforced at the controller (permission + StoreAuthorizationService) so
 * this service can focus on pagination + entity-graph loading without
 * re-checking actor context on every call.
 */
@Service
public class ProductionOrderQueryService {

    private final ProductionOrderRepository orders;
    private final ProductionOrderMapper mapper;

    public ProductionOrderQueryService(ProductionOrderRepository orders, ProductionOrderMapper mapper) {
        this.orders = orders;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page<ProductionOrderResponse> list(UUID storeId, Optional<ProductionOrderStatus> status, int page,
            int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)));
        Page<ProductionOrder> rows = status.isPresent()
                ? orders.findByStoreIdAndStatusIn(storeId, List.of(status.get()), pageable)
                : orders.findByStoreId(storeId, pageable);
        return rows.map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductionOrderResponse get(UUID productionOrderId) {
        return orders.findWithChildrenById(productionOrderId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCTION_ORDER_NOT_FOUND",
                        "Production order not found"));
    }

    @Transactional(readOnly = true)
    public List<ProductionOrderResponse.MaterialLine> materials(UUID productionOrderId) {
        var order = orders.findWithChildrenById(productionOrderId).orElseThrow(() -> new ResourceNotFoundException(
                "PRODUCTION_ORDER_NOT_FOUND", "Production order not found"));
        return mapper.toResponse(order).materials();
    }
}
