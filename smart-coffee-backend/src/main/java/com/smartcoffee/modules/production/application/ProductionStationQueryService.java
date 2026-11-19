package com.smartcoffee.modules.production.application;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import com.smartcoffee.modules.production.api.ProductionStationResponse;
import com.smartcoffee.modules.production.domain.ProductionStation;
import com.smartcoffee.modules.production.persistence.ProductionStationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protected production-station discovery (Checkpoint 4). Single authority for
 * the station list a branch exposes to management, KDS/BDS and POS clients.
 *
 * <p>Server-enforced rules:
 * <ul>
 * <li>the branch must exist (404) and the actor must have access to it (403);
 * the caller never supplies a station list from the client;</li>
 * <li>only ACTIVE stations are returned by default;</li>
 * <li>inactive stations are revealed solely to an actor holding
 * {@code production:station-manage} (administrative configuration use case);
 * requesting them without that permission is a 403, not a silent filter;</li>
 * <li>ordering is deterministic (display order ascending).</li>
 * </ul>
 */
@Service
public class ProductionStationQueryService {

    private final ProductionStationRepository stations;
    private final StoreRepository stores;
    private final PermissionEvaluator permissions;

    public ProductionStationQueryService(ProductionStationRepository stations, StoreRepository stores,
            PermissionEvaluator permissions) {
        this.stations = stations;
        this.stores = stores;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<ProductionStationResponse> discover(CurrentActor actor, UUID storeId, boolean includeInactive) {
        requireStoreAccess(actor, storeId);
        permissions.require(actor, PermissionCodes.PRODUCTION_STATION_READ);
        if (includeInactive) {
            // Administrative view: revealing disabled stations is its own grant.
            permissions.require(actor, PermissionCodes.PRODUCTION_STATION_MANAGE);
            return stations.findByStoreIdOrderByDisplayOrderAscStoreIdAsc(storeId).stream()
                    .map(ProductionStationQueryService::toResponse).toList();
        }
        return stations.findByStoreIdAndActiveTrueOrderByDisplayOrderAscStoreIdAsc(storeId).stream()
                .map(ProductionStationQueryService::toResponse).toList();
    }

    private void requireStoreAccess(CurrentActor actor, UUID storeId) {
        stores.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "Branch not found"));
        if (!actor.canAccessStore(storeId)) {
            throw new ForbiddenException("STORE_ACCESS_DENIED", "No access to this branch");
        }
    }

    private static ProductionStationResponse toResponse(ProductionStation s) {
        return new ProductionStationResponse(s.id(), s.storeId(), s.code(), s.name(), s.type().name(),
                s.displayOrder(), s.active(), s.version());
    }
}
