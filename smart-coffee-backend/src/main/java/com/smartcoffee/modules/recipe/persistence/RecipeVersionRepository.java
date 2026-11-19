package com.smartcoffee.modules.recipe.persistence;

import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RecipeVersionRepository extends JpaRepository<RecipeVersion, UUID> {

    /** Loads a single version with its lines eagerly (avoids N+1 on the read/preview path). */
    @EntityGraph(attributePaths = "lines")
    @Query("select r from RecipeVersion r where r.id = :id")
    Optional<RecipeVersion> findWithLinesById(@Param("id") UUID id);

    List<RecipeVersion> findByProductIdOrderByVersionNumberDesc(UUID productId);

    boolean existsByProductIdAndStatus(UUID productId, RecipeStatus status);

    @Query("select coalesce(max(r.versionNumber), 0) from RecipeVersion r where r.productId = :p")
    int maxVersionNumber(@Param("p") UUID product);
}
