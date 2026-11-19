package com.smartcoffee.modules.loyalty.persistence;

import com.smartcoffee.modules.loyalty.domain.LoyaltyAccount;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LoyaltyAccount a where a.customerId=:customer")
    Optional<LoyaltyAccount> lockByCustomer(@Param("customer") UUID customer);

    Optional<LoyaltyAccount> findByCustomerId(UUID customer);
}

