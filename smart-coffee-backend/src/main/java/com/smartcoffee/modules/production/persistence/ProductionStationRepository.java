package com.smartcoffee.modules.production.persistence;

import com.smartcoffee.modules.production.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionStationRepository extends JpaRepository<ProductionStation, UUID> {
    Optional<ProductionStation> findByStoreIdAndCodeAndActiveTrue(UUID store, String code);

    Optional<ProductionStation> findByStoreIdAndTypeAndActiveTrue(UUID store, StationType type);

    // Checkpoint 3B: distinguishes "station does not exist" from "station is
    // inactive" instead of collapsing both into one error.
    Optional<ProductionStation> findByStoreIdAndCode(UUID store, String code);

    // Checkpoint 4 station discovery.
    java.util.List<ProductionStation> findByStoreIdOrderByDisplayOrderAscStoreIdAsc(UUID store);

    java.util.List<ProductionStation> findByStoreIdAndActiveTrueOrderByDisplayOrderAscStoreIdAsc(UUID store);
}

