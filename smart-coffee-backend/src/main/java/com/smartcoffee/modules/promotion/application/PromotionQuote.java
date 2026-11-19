package com.smartcoffee.modules.promotion.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Advisory promotion quote. Phase B1 performs read-side evaluation only: no
 * redemption is written and no usage is consumed, so this result does not
 * guarantee capacity remains until Phase B2 reserves atomically during checkout
 * (Phase B1 §6).
 */
public record PromotionQuote(UUID promotionId, String code, String name, boolean eligible,
        PromotionReasonCode reasonCode, BigDecimal eligibleSubtotal, BigDecimal discountAmount, String currencyCode,
        List<UUID> affectedCartItemIds, long promotionVersion) {
    public PromotionQuote {
        affectedCartItemIds = affectedCartItemIds == null ? List.of() : List.copyOf(affectedCartItemIds);
    }
}
