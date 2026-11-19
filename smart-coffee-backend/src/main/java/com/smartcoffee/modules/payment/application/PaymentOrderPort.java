package com.smartcoffee.modules.payment.application;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentOrderPort {
    OrderPaymentView loadOwned(UUID orderId, UUID userId);

    /**
     * Administrative read WITHOUT customer ownership (Full-stack wiring §1.3,
     * receipt re-print for staff): callers must independently enforce
     * order:admin-read and store scoping before trusting the returned view.
     */
    OrderPaymentView load(UUID orderId);

    void confirm(UUID orderId, UUID actorId);

    void expire(UUID orderId);

    record OrderPaymentView(UUID orderId, UUID storeId, UUID customerId, String currency, BigDecimal total,
            BigDecimal tax, String status, String orderNumber, BigDecimal subtotal, BigDecimal discount,
            UUID promotionId, String promotionCode, String promotionName, String promotionType,
            BigDecimal promotionValue, BigDecimal promotionEligibleSubtotal, BigDecimal promotionDiscountAmount,
            Long promotionVersion) {
    }
}

