package com.smartcoffee.modules.loyalty.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.loyalty.api.LoyaltyAccountResponse;
import com.smartcoffee.modules.loyalty.persistence.LoyaltyAccountRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoyaltyQueryService {
    private final LoyaltyAccountRepository accounts;

    public LoyaltyQueryService(LoyaltyAccountRepository a) {
        accounts = a;
    }

    @Transactional(readOnly = true)
    public LoyaltyAccountResponse get(UUID customer) {
        var a = accounts.findByCustomerId(customer)
                .orElseThrow(() -> new ResourceNotFoundException("Loyalty account not found"));
        return new LoyaltyAccountResponse(a.id(), a.customerId(), a.available(), a.reserved(), a.lifetimeEarned(),
                a.lifetimeRedeemed(), a.version());
    }
}

