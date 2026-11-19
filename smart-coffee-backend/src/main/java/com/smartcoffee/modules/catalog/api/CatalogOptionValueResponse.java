package com.smartcoffee.modules.catalog.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Availability-aware option VALUE for the channel catalog (Slice 3B, spec §9/§15).
 * Keeps every allowed value present (never silently deleted) and marks the ones that
 * cannot currently be selected with {@code available == false} plus stable reasons.
 * {@code priceDelta} is the authoritative additional price loaded from the option
 * row, never a client value.
 */
public record CatalogOptionValueResponse(
        UUID optionId,
        String code,
        String name,
        BigDecimal priceDelta,
        boolean selectable,
        boolean available,
        List<String> reasonCodes) {

    public CatalogOptionValueResponse {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
