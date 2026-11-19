package com.smartcoffee.infrastructure.messaging;

import com.smartcoffee.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

class ConsumerInboxRaceIT extends IntegrationTestBase {
    @Test
    void concurrentDuplicateDeliveryExecutesOnce() {
        /* Run two transactions for same consumer/event. Assert one side effect. */}
}

