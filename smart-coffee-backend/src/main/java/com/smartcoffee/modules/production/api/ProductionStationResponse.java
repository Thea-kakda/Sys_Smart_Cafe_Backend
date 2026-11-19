package com.smartcoffee.modules.production.api;

import java.util.UUID;

/**
 * Production station discovery row (Checkpoint 4). Every field is an
 * authoritative database value; the frontend renders this list and never
 * invents stations or rewrites their type. {@code stationType} is the routing
 * category (BARISTA/KITCHEN/BAKERY/PACKING/PICKUP); {@code stationCode} is the
 * branch-unique code referenced by catalog.store_products.
 */
public record ProductionStationResponse(
        UUID stationId,
        UUID storeId,
        String stationCode,
        String stationName,
        String stationType,
        int displayOrder,
        boolean active,
        long version) {
}
