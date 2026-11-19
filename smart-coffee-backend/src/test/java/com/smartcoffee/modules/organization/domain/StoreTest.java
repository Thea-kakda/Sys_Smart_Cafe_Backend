package com.smartcoffee.modules.organization.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StoreTest {

    @Test
    void createAssignsIdentityDefaultsAndTrimsBusinessFields() {
        Store store = Store.create("  BKK3  ", "  Smart Coffee BKK3 ", "Addr", "  +855 12  ", "Asia/Phnom_Penh", "USD",
                "km-KH", true);

        assertNotNull(store.id());
        assertEquals("BKK3", store.code());
        assertEquals("Smart Coffee BKK3", store.name());
        assertTrue(store.active());
        assertEquals(0L, store.version());
        assertNotNull(store.createdAt());
        assertEquals(store.createdAt(), store.updatedAt());
    }

    @Test
    void createDefaultsToVersionZeroSoClientCannotForgeLock() {
        Store store = Store.create("BKK4", "Name", "Addr", null, "Asia/Phnom_Penh", "KHR", "en-US", false);
        assertEquals(0L, store.version());
        assertFalse(store.active());
    }

    @Test
    void updateProfileAdvancesTimestampButKeepsCodeImmutable() {
        Store store = Store.create("BKK5", "Old", "OldAddr", null, "Asia/Phnom_Penh", "USD", "km-KH", true);
        Instant before = store.updatedAt();

        store.updateProfile("New", "NewAddr", "+855 99", "Asia/Bangkok", "KHR", "en-US");

        assertEquals("BKK5", store.code());
        assertEquals("New", store.name());
        assertEquals("KHR", store.defaultCurrency());
        assertFalse(store.updatedAt().isBefore(before));
    }

    @Test
    void changeStatusTogglesActiveFlag() {
        Store store = Store.create("BKK6", "Name", "Addr", null, "Asia/Phnom_Penh", "USD", "km-KH", true);

        store.changeStatus(false);
        assertFalse(store.active());
    }
}
