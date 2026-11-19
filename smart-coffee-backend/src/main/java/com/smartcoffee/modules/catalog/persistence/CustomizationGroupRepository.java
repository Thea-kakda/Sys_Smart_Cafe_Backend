package com.smartcoffee.modules.catalog.persistence;

import com.smartcoffee.modules.catalog.domain.CustomizationGroup;

import java.util.UUID;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomizationGroupRepository
        extends JpaRepository<CustomizationGroup, UUID> {

    @Query("""
        SELECT g
        FROM CustomizationGroup g
        JOIN ProductCustomizationGroup pg
            ON pg.id.groupId = g.id
        WHERE pg.id.productId = :productId
          AND g.active = true
        ORDER BY pg.displayOrder, g.displayOrder
        """)
    List<CustomizationGroup> findForProduct(
            @Param("productId") UUID productId
    );

    /** Management read: every master group, inactive included, in editor order. */
    List<CustomizationGroup> findAllByOrderByDisplayOrderAscNameAsc();

    /** Duplicate pre-check for the master create path (group_code is globally unique). */
    Optional<CustomizationGroup> findByCodeIgnoreCase(String code);
}

