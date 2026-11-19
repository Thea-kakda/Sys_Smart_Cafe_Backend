package com.smartcoffee.modules.catalog.persistence;

import com.smartcoffee.modules.catalog.domain.ProductCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, UUID> {

    List<ProductCategory> findByActiveTrueOrderByDisplayOrderAscNameAsc();

    List<ProductCategory> findAllByOrderByDisplayOrderAscNameAsc();

    Optional<ProductCategory> findByIdAndActiveTrue(UUID id);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);
}
