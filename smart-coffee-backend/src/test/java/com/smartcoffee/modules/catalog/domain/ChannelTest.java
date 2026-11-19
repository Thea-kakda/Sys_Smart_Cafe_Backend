package com.smartcoffee.modules.catalog.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Channel} publication policy primitive (Slice 3B, spec §3/§8).
 * These lock the two invariants the catalog read and the migration both rely on: the
 * full six-value enum exists, and only the three integrated surfaces inherit legacy
 * visibility when no explicit channel row is present. The deferred channels must
 * default hidden so an operator has to publish them deliberately.
 */
class ChannelTest {

    @Test
    void fullExtensibleEnumIsDeclared() {
        assertEquals(6, Channel.values().length);
        for (String name : new String[] { "POS", "CUSTOMER_WEB", "CUSTOMER_APP", "QR_MENU", "KIOSK", "DELIVERY" }) {
            assertTrue(Channel.valueOf(name) != null, name + " must exist");
        }
    }

    @Test
    void integratedSurfacesInheritLegacyVisibility() {
        assertTrue(Channel.POS.inheritsLegacyVisibility());
        assertTrue(Channel.CUSTOMER_WEB.inheritsLegacyVisibility());
        assertTrue(Channel.CUSTOMER_APP.inheritsLegacyVisibility(),
                "app routes through the same safe customer projection when unconfigured");
    }

    @Test
    void deferredChannelsAreHiddenByDefault() {
        assertFalse(Channel.QR_MENU.inheritsLegacyVisibility());
        assertFalse(Channel.KIOSK.inheritsLegacyVisibility());
        assertFalse(Channel.DELIVERY.inheritsLegacyVisibility());
    }

    @Test
    void fromParsesCaseAndWhitespaceTolerantly() {
        assertEquals(Channel.POS, Channel.from("pos"));
        assertEquals(Channel.CUSTOMER_WEB, Channel.from("  CUSTOMER_WEB  "));
    }

    @Test
    void fromRejectsUnknownAndBlank() {
        assertThrows(IllegalArgumentException.class, () -> Channel.from("TELEGRAM"));
        assertThrows(IllegalArgumentException.class, () -> Channel.from("   "));
        assertThrows(IllegalArgumentException.class, () -> Channel.from(null));
    }
}
