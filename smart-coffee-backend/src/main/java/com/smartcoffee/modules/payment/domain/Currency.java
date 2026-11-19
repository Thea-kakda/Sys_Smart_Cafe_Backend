package com.smartcoffee.modules.payment.domain;

import com.smartcoffee.common.exception.ValidationException;

/**
 * Supported settlement/tender currencies (Checkpoint 5A). This enum is the
 * single backend allowlist: an unsupported currency is a client error, never a
 * silent fallback. It mirrors the {@code ck_xrate_*_currency} check in V019.
 *
 * <p>{@code minorUnits} is the documented rounding scale for a currency and is
 * the authoritative target scale for conversions (Checkpoint 5B): USD to cents
 * (2), KHR to whole riel (0).
 */
public enum Currency {
    USD(2), KHR(0);

    private final int minorUnits;

    Currency(int minorUnits) {
        this.minorUnits = minorUnits;
    }

    public int minorUnits() {
        return minorUnits;
    }

    public static Currency parse(String code) {
        if (code == null || code.isBlank()) {
            throw new ValidationException("UNSUPPORTED_CURRENCY", "Currency is required");
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("UNSUPPORTED_CURRENCY", "Unsupported currency: " + code);
        }
    }
}
