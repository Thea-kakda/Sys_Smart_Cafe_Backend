package com.smartcoffee.infrastructure.messaging.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRecoveryJob {
    @Scheduled(fixedDelayString = "${app.outbox.recovery-delay-ms:60000}")
    public void recover() {
        /*
         * Implement stale PROCESSING recovery after repository claim strategy is
         * finalized.
         */ }
}

