package com.smartcoffee.modules.catalog.persistence;

import com.smartcoffee.modules.catalog.domain.ProductCustomizationOption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Product/option allow-list rows. Reads are scoped by product (the resolver
 * loads a whole product's allow-list in one query and groups it in memory) and,
 * for the management write diff, by the exact {@code (product, group)} pair.
 */
public interface ProductCustomizationOptionRepository
        extends JpaRepository<ProductCustomizationOption, UUID> {

    /** Every active allow-list row of a product, grouped by caller. */
    List<ProductCustomizationOption> findByProductIdAndActiveTrue(UUID productId);

    /** Active allow-list rows of one product/group attachment, in per-product order. */
    List<ProductCustomizationOption> findByProductIdAndGroupIdAndActiveTrueOrderByDisplayOrderAsc(
            UUID productId, UUID groupId);

    ProductCustomizationOption findByProductIdAndGroupIdAndOptionId(UUID productId, UUID groupId, UUID optionId);
}
