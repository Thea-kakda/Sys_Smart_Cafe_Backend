package com.smartcoffee.modules.loyalty.application;

import com.smartcoffee.modules.loyalty.domain.*;
import com.smartcoffee.modules.loyalty.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoyaltyPointService {
    private final LoyaltyAccountRepository accounts;
    private final LoyaltyTransactionRepository ledger;

    public LoyaltyPointService(LoyaltyAccountRepository a, LoyaltyTransactionRepository l) {
        accounts = a;
        ledger = l;
    }

    @Transactional
    public void earn(UUID customer, UUID order, UUID event, BigDecimal eligibleAmount) {
        if (ledger.existsBySourceEventId(event))
            return;
        var a = accounts.lockByCustomer(customer).orElseGet(() -> accounts.save(LoyaltyAccount.create(customer)));
        long before = a.available();
        long points = eligibleAmount.setScale(0, java.math.RoundingMode.FLOOR).longValue();
        if (points <= 0)
            return;
        a.earn(points);
        ledger.save(LoyaltyTransaction.create(a.id(), LoyaltyTransactionType.EARN, points, before, a.available(), order,
                event));
    }

    @Transactional
    public void reserve(UUID customer, UUID order, long points) {
        var a = accounts.lockByCustomer(customer).orElseThrow();
        long before = a.available();
        a.reserve(points);
        ledger.save(LoyaltyTransaction.create(a.id(), LoyaltyTransactionType.RESERVE, -points, before, a.available(),
                order, null));
    }

    @Transactional
    public void redeem(UUID customer, UUID order, long points) {
        var a = accounts.lockByCustomer(customer).orElseThrow();
        long before = a.available();
        a.redeem(points);
        ledger.save(LoyaltyTransaction.create(a.id(), LoyaltyTransactionType.REDEEM, 0, before, a.available(), order,
                null));
    }

    @Transactional
    public void release(UUID customer, UUID order, long points) {
        var a = accounts.lockByCustomer(customer).orElseThrow();
        long before = a.available();
        a.release(points);
        ledger.save(LoyaltyTransaction.create(a.id(), LoyaltyTransactionType.RELEASE, points, before, a.available(),
                order, null));
    }
}

