package com.smartcoffee.modules.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One master customization option as the Dashboard Options tab needs it
 * (Slice 4A Round 2, §7): every editable field plus the optimistic-lock
 * version the editor must echo back, and the option's current inventory
 * effect (managed through its own dedicated endpoint, surfaced here so the
 * editor renders one row per option without a second round-trip).
 */
public record CustomizationOptionMasterResponse(
        UUID optionId,
        String code,
        String name,
        BigDecimal additionalPrice,
        int displayOrder,
        boolean active,
        long version,
        InventoryEffectResponse inventoryEffect) {
}
