package com.smartcoffee.modules.loyalty.persistence;

import com.smartcoffee.modules.loyalty.domain.LoyaltyTransaction;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, UUID> {
    boolean existsBySourceEventId(UUID id);

    List<LoyaltyTransaction> findByAccountIdOrderByOccurredAtDesc(UUID account, Pageable page);
}

