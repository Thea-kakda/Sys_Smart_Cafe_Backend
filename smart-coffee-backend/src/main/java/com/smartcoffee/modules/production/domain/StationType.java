package com.smartcoffee.modules.production.domain;

import com.smartcoffee.common.exception.ValidationException;

public enum StationType {
    BARISTA, KITCHEN, BAKERY, PACKING, PICKUP;

    /**
     * Checkpoint 4: routing categories are a fixed vocabulary. Parsing a
     * client-supplied station token must not surface {@link #valueOf} as an
     * unhandled 500; an unknown value is a client error mapped to a safe 400
     * ({@code INVALID_STATION}).
     */
    public static StationType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("INVALID_STATION", "Production station is required");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("INVALID_STATION", "Unknown production station: " + value);
        }
    }
}
