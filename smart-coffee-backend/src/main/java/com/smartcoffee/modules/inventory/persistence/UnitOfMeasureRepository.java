package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasure, UUID> {
    Optional<UnitOfMeasure> findByCodeAndActiveTrue(String code);

    List<UnitOfMeasure> findByActiveTrueOrderByTypeAscCodeAsc();

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);
}
