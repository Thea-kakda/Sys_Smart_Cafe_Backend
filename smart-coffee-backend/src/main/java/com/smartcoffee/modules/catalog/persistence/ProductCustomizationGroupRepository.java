package com.smartcoffee.modules.catalog.persistence;

import com.smartcoffee.modules.catalog.domain.ProductCustomizationGroup;
import com.smartcoffee.modules.catalog.domain.ProductCustomizationGroupId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Attachment rows of one product. Unlike the customer-facing
 * {@link CustomizationGroupRepository#findForProduct(UUID)} — which hides
 * groups whose master row was deactivated — the management read deliberately
 * returns <em>every</em> attached group (inactive masters included, flagged in
 * the projection) because an editor must be able to see and detach what is
 * actually on the row instead of silently losing it.
 */
public interface ProductCustomizationGroupRepository
        extends JpaRepository<ProductCustomizationGroup, ProductCustomizationGroupId> {

    @Query("""
        select a
        from ProductCustomizationGroup a
        where a.id.productId = :productId
        order by a.displayOrder asc, a.id.groupId asc
        """)
    List<ProductCustomizationGroup> findAllForProduct(@Param("productId") UUID productId);

    ProductCustomizationGroup findByIdProductIdAndIdGroupId(UUID productId, UUID groupId);

    /**
     * Every attachment of one group across products. Used by the Round 2 master
     * update path: shrinking master limits must not break a product whose stored
     * override inherits past the new cap.
     */
    List<ProductCustomizationGroup> findByIdGroupId(UUID groupId);
}
