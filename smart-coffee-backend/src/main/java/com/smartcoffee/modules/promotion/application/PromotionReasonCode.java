package com.smartcoffee.modules.promotion.application;

/**
 * Stable rejection contract for promotion evaluation. Codes are part of the API
 * surface: callers may translate them, so they must never change silently and
 * must never carry SQL or exception text (Phase B1 §5).
 */
public enum PromotionReasonCode {
    PROMOTION_NOT_FOUND,
    PROMOTION_NOT_ACTIVE,
    PROMOTION_NOT_STARTED,
    PROMOTION_EXPIRED,
    PROMOTION_STORE_NOT_ELIGIBLE,
    PROMOTION_CURRENCY_MISMATCH,
    PROMOTION_CUSTOMER_NOT_ELIGIBLE,
    PROMOTION_NO_ELIGIBLE_ITEMS,
    PROMOTION_MINIMUM_NOT_MET,
    PROMOTION_USAGE_LIMIT_REACHED,
    PROMOTION_CUSTOMER_LIMIT_REACHED,
    PROMOTION_INVALID_CONFIGURATION
}
