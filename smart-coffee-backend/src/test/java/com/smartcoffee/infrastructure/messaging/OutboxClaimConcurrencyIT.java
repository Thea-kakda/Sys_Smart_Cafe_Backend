package com.smartcoffee.infrastructure.messaging;

import com.smartcoffee.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

class OutboxClaimConcurrencyIT extends IntegrationTestBase {
    @Test
    void publishersClaimDifferentRows() {
        /*
         * Seed pending events, run two claimers, assert disjoint IDs and eventual
         * publication.
         */}
}

