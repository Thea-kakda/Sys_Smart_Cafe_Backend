package com.smartcoffee.modules.payment.api;

import com.smartcoffee.modules.payment.domain.PaymentConversion;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Exposes the immutable currency-conversion trail of a settled cash payment
 * (Checkpoint 5D). Present only for payments that involved a tender/settlement
 * currency decision; null otherwise (e.g. KHQR). The values are read straight
 * from the persisted snapshot, so they never change when the rate table is
 * edited later.
 */
public record PaymentConversionResponse(
        String tenderCurrency,
        BigDecimal tenderAmountReceived,
        UUID exchangeRateId,
        BigDecimal appliedExchangeRate,
        String rateBaseCurrency,
        String rateQuoteCurrency,
        boolean exchangeRateInverted,
        BigDecimal settlementAmountReceived,
        BigDecimal settlementChangeAmount,
        BigDecimal tenderChangeAmount,
        String changeCurrency,
        BigDecimal roundingAmount) {

    public static PaymentConversionResponse from(PaymentConversion c) {
        if (c == null) {
            return null;
        }
        return new PaymentConversionResponse(c.tenderCurrency(), c.tenderAmountReceived(), c.exchangeRateId(),
                c.appliedExchangeRate(), c.rateBaseCurrency(), c.rateQuoteCurrency(), c.exchangeRateInverted(),
                c.settlementAmountReceived(), c.settlementChangeAmount(), c.tenderChangeAmount(), c.changeCurrency(),
                c.roundingAmount());
    }
}
