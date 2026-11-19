package com.smartcoffee.modules.promotion.persistence;

import com.smartcoffee.modules.promotion.domain.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {
    Optional<Promotion> findByCodeAndStatus(String code, PromotionStatus status);

    Optional<Promotion> findByCode(String code);

    boolean existsByCode(String code);

    Page<Promotion> findByStatus(PromotionStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Promotion p where p.id=:id")
    Optional<Promotion> lock(@Param("id") UUID id);

    @Query("select p from Promotion p where p.status='ACTIVE' and p.automatic=true and p.startsAt<=:now and p.endsAt>:now order by p.priority desc")
    List<Promotion> automatic(@Param("now") Instant now);

    @Modifying
    @Query(value = "update promotion.promotions set usage_count=usage_count+1,version=version+1 where promotion_id=:id and (usage_limit is null or usage_count<usage_limit)", nativeQuery = true)
    int claim(@Param("id") UUID id);

    @Modifying
    @Query(value = "update promotion.promotions set usage_count=greatest(usage_count-1,0),version=version+1 where promotion_id=:id", nativeQuery = true)
    int releaseClaim(@Param("id") UUID id);
}

