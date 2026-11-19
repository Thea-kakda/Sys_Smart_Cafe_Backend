package com.smartcoffee.modules.production.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

/**
 * Checkpoint 4: invalid station tokens must map to a safe 400
 * ({@code INVALID_STATION}) instead of surfacing {@code valueOf} as an
 * unhandled 500.
 */
class StationTypeTest {

    @Test
    void parsesKnownValuesCaseAndWhitespaceInsensitively() {
        assertEquals(StationType.BARISTA, StationType.parse("barista"));
        assertEquals(StationType.KITCHEN, StationType.parse("  Kitchen  "));
        assertEquals(StationType.PICKUP, StationType.parse("PICKUP"));
    }

    @Test
    void rejectsUnknownValueWithSafe400() {
        var ex = assertThrows(ValidationException.class, () -> StationType.parse("DRIVER"));
        assertEquals("INVALID_STATION", ex.code());
        assertEquals(400, ex.status());
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(ValidationException.class, () -> StationType.parse("   "));
        assertThrows(ValidationException.class, () -> StationType.parse(null));
    }
}
