package com.smartcoffee.modules.payment.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable, self-contained record of how a cash payment was settled across
 * currencies (Checkpoint 5D). Persisted denormalized on the payment row so the
 * financial transaction can be reconstructed forever, independent of the live
 * rate table: changing or removing an exchange rate later must NEVER alter an
 * old payment, its receipt, its change, its audit trail or an idempotent
 * replay. There is deliberately no foreign key to {@code currency_exchange_rates}.
 *
 * @param tenderCurrency         currency of the physical cash handed over
 * @param tenderAmountReceived   amount of cash received in {@code tenderCurrency}
 * @param exchangeRateId         rate row used (null for a same-currency identity)
 * @param appliedExchangeRate    rate actually applied, in stored base-&gt;quote direction
 * @param rateBaseCurrency       base currency of the rate row used
 * @param rateQuoteCurrency      quote currency of the rate row used
 * @param exchangeRateInverted   whether the stored rate was inverted to settle
 * @param settlementAmountReceived value of the tender in the order/settlement currency
 * @param settlementChangeAmount change owed, in the order/settlement currency
 * @param tenderChangeAmount     change owed, returned in the tender currency
 * @param changeCurrency         currency the change is physically given in (the tender)
 * @param roundingAmount         residual introduced by rounding to minor units
 */
public record PaymentConversion(
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
}
