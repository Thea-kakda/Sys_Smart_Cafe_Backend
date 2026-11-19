package com.smartcoffee.modules.production.application;

import java.util.*;

public interface ProductionOrderPort {
    OrderProductionView load(UUID orderId);

    void markPreparing(UUID orderId, UUID actor);

    void markReady(UUID orderId, UUID actor);

    record OrderProductionView(UUID orderId, UUID storeId, String orderNumber, String status, List<ItemView> items) {
    }

    record ItemView(UUID orderItemId, UUID productId, String productName, int quantity, String stationCode,
            List<String> customizations) {
    }
}

