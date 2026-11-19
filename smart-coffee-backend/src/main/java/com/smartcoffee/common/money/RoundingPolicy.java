package com.smartcoffee.common.money;

import java.math.*;

public interface RoundingPolicy {
    BigDecimal round(BigDecimal value, String currency);

    static RoundingPolicy currencyScale() {
        return (v, c) -> v.setScale("KHR".equals(c) ? 0 : 2, RoundingMode.HALF_UP);
    }
}

