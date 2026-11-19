package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.modules.inventory.api.BatchExpiryResponse;
import com.smartcoffee.modules.inventory.persistence.BatchQueryRepository.BatchRow;

/**
 * Maps the batch read projection to its response DTO. Shared by
 * {@link BatchQueryService} (the list) and {@link BatchService} (the write-off
 * echo), keeping the projection -> wire shape in one place.
 */
final class BatchMapper {
    private BatchMapper() {
    }

    static BatchExpiryResponse toResponse(BatchRow r) {
        return new BatchExpiryResponse(r.getBatchId(), r.getStoreId(), r.getIngredientId(), r.getIngredientCode(),
                r.getIngredientName(), r.getUnitCode(), r.getLotNumber(), r.getExpiryDate(), r.getQuantityBase(),
                r.getUnitCost(), r.getStatus(), r.getDaysToExpiry(), r.getReceivedAt(), r.getVersion());
    }
}
