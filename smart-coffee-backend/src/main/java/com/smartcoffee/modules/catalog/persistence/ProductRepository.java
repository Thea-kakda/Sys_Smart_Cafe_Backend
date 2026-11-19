package com.smartcoffee.modules.catalog.persistence;

import com.smartcoffee.modules.catalog.domain.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findByIdAndActiveTrue(UUID id);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    Optional<Product> findByCode(String code);

    /** Archive guard: a version still referenced as some product's fallback cannot be archived. */
    boolean existsByActiveRecipeVersionId(UUID recipeVersionId);

    /**
     * Management search over the product master (any active state). Blank search
     * and null category are treated as "no filter", consistent with the
     * branch-product list contract.
     */
    @Query("select p from Product p where "
            + "(:search = '' or lower(p.name) like lower(concat('%', :search, '%')) "
            + "or lower(p.code) like lower(concat('%', :search, '%'))) "
            + "and (:categoryId is null or p.categoryId = :categoryId)")
    Page<Product> search(@Param("search") String search, @Param("categoryId") UUID categoryId, Pageable pageable);
}
