package com.smartcoffee.common.money;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class MoneyCalculator {
    private final RoundingPolicy rounding = RoundingPolicy.currencyScale();

    public Money total(Money subtotal, Money discount, Money tax, Money fee, Money adjustment) {
        return new Money(rounding.round(subtotal.amount().subtract(discount.amount()).add(tax.amount())
                .add(fee.amount()).add(adjustment.amount()), subtotal.currency()), subtotal.currency());
    }

    public BigDecimal round(BigDecimal value, String currency) {
        return rounding.round(value, currency);
    }
}

