package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.Ingredient;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngredientRepository extends JpaRepository<Ingredient, UUID> {
    Optional<Ingredient> findByIdAndActiveTrue(UUID id);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    @Query("select i from Ingredient i where lower(i.code) like lower(concat('%', :q, '%')) "
            + "or lower(i.name) like lower(concat('%', :q, '%')) order by i.code")
    Page<Ingredient> search(@Param("q") String query, Pageable pageable);
}
