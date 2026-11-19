package com.smartcoffee.infrastructure.messaging.inbox;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumerInboxRepository extends JpaRepository<ConsumerInbox, UUID> {
    boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);
}

