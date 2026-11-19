package com.smartcoffee.modules.organization.api;

import java.util.UUID;

public record StoreResponse(
        UUID storeId,
        String storeCode,
        String storeName,
        String address,
        String phone,
        String timezone,
        String defaultCurrency,
        String defaultLanguage,
        boolean open,
        boolean active) {}

