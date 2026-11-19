package com.smartcoffee.common.money;

import java.math.*;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {
    public Money {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        if (currency.length() != 3)
            throw new IllegalArgumentException("currency must be ISO-4217 code");
    }

    public Money add(Money other) {
        same(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        same(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    private void same(Money o) {
        if (!currency.equals(o.currency))
            throw new IllegalArgumentException("Currency mismatch");
    }
}

