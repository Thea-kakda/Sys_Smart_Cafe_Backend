package com.smartcoffee.modules.production.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.production.api.ProductionTicketResponse;
import com.smartcoffee.modules.production.domain.*;
import com.smartcoffee.modules.production.persistence.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionQueueQueryService {
    private final ProductionStationRepository stations;
    private final ProductionTicketRepository tickets;
    private final ProductionMapper mapper;

    public ProductionQueueQueryService(ProductionStationRepository s, ProductionTicketRepository t,
            ProductionMapper m) {
        stations = s;
        tickets = t;
        mapper = m;
    }

    @Transactional(readOnly = true)
    public List<ProductionTicketResponse> queue(UUID store, StationType type) {
        var station = stations.findByStoreIdAndTypeAndActiveTrue(store, type)
                .orElseThrow(() -> new ResourceNotFoundException("Production station not found"));
        return tickets
                .findByStoreIdAndStationIdAndStatusInOrderByPriorityDescQueuedAtAsc(store, station.id(),
                        List.of(ProductionStatus.PENDING, ProductionStatus.PREPARING))
                .stream().map(t -> mapper.response(t, station)).toList();
    }
}

