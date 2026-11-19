package com.smartcoffee.modules.catalog.persistence;

import com.smartcoffee.modules.catalog.domain.ProductVariant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence port for {@link ProductVariant}. Soft-deleted rows are excluded
 * from all read queries; callers that need history (order snapshots) resolve
 * the id directly and receive an empty Optional after delete.
 */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    @Query("select v from ProductVariant v where v.productId = :productId and v.deletedAt is null "
            + "order by v.displayOrder asc, v.name asc")
    List<ProductVariant> findActiveByProduct(@Param("productId") UUID productId);

    Optional<ProductVariant> findByIdAndDeletedAtIsNull(UUID id);

    @Query("select v from ProductVariant v where v.productId = :productId and v.deletedAt is null "
            + "and v.active = true and v.defaultVariant = true")
    Optional<ProductVariant> findActiveDefault(@Param("productId") UUID productId);

    boolean existsByProductIdAndCodeAndDeletedAtIsNull(UUID productId, String code);

    @Query("select count(v) from ProductVariant v where v.productId = :productId and v.deletedAt is null")
    long countByProduct(@Param("productId") UUID productId);

    /**
     * Non-deleted variants that explicitly pin the given recipe-version override.
     * Used by the Slice 2A guard that refuses to supersede/archive a version
     * that is still assigned (unless the same transaction reassigns it).
     */
    @Query("select v from ProductVariant v where v.activeRecipeVersionId = :recipeVersionId and v.deletedAt is null")
    List<ProductVariant> findAssignedToRecipe(@Param("recipeVersionId") UUID recipeVersionId);
}
