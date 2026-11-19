package com.smartcoffee.modules.promotion.persistence;

import com.smartcoffee.modules.promotion.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PromotionRedemptionRepository extends JpaRepository<PromotionRedemption, UUID> {
    long countByPromotionIdAndCustomerIdAndStatusIn(UUID promotion, UUID customer,
            Collection<RedemptionStatus> statuses);

    Optional<PromotionRedemption> findByPromotionIdAndOrderId(UUID promotion, UUID order);

    List<PromotionRedemption> findByOrderId(UUID order);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PromotionRedemption r where r.orderId=:orderId")
    List<PromotionRedemption> lockByOrderId(@Param("orderId") UUID orderId);
}
