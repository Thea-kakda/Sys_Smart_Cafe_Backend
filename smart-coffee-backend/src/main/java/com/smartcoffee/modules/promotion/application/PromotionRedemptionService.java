package com.smartcoffee.modules.promotion.application;

import com.smartcoffee.common.exception.*;
import com.smartcoffee.modules.promotion.api.*;
import com.smartcoffee.modules.promotion.domain.*;
import com.smartcoffee.modules.promotion.persistence.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.temporal.ChronoUnit;

@Service
public class PromotionRedemptionService {
    private final PromotionEligibilityService eligibility;
    private final PromotionRepository promotions;
    private final PromotionRedemptionRepository redemptions;

    public PromotionRedemptionService(PromotionEligibilityService e, PromotionRepository p,
            PromotionRedemptionRepository r) {
        eligibility = e;
        promotions = p;
        redemptions = r;
    }

    @Transactional
    public PromotionRedemption reserve(PromotionValidationRequest q) {
        var v = eligibility.validate(q);
        if (!v.eligible())
            throw new ConflictException("PROMOTION_NOT_ELIGIBLE", v.reason());
        if (redemptions.findByPromotionIdAndOrderId(v.promotionId(), q.orderId()).isPresent())
            return redemptions.findByPromotionIdAndOrderId(v.promotionId(), q.orderId()).orElseThrow();
        if (promotions.claim(v.promotionId()) != 1)
            throw new ConflictException("PROMOTION_USAGE_EXHAUSTED", "Promotion usage exhausted");
        return redemptions.save(PromotionRedemption.reserve(v.promotionId(), q.customerId(), q.orderId(), q.storeId(),
                v.discountAmount(), q.currencyCode(), Instant.now().plus(15, ChronoUnit.MINUTES)));
    }

    @Transactional
    public void releaseForOrder(java.util.UUID order) {
        for (var r : redemptions.findByOrderId(order))
            if (r.status() == RedemptionStatus.RESERVED) {
                r.release();
                promotions.releaseClaim(r.promotionId());
            }
    }

    @Transactional
    public void redeemForOrder(java.util.UUID order) {
        for (var r : redemptions.findByOrderId(order))
            if (r.status() == RedemptionStatus.RESERVED)
                r.redeem();
    }
}

