package com.smartcoffee.modules.inventory.api;

import java.time.Instant;
import java.util.UUID;

public record SupplierResponse(UUID supplierId, String code, String name, String contactName, String phone,
        String email, String address, String taxId, boolean active, Instant createdAt, Instant updatedAt,
        long version) {
}
