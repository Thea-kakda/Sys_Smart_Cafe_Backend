package com.smartcoffee.modules.loyalty.api;

import java.util.UUID;

public record LoyaltyAccountResponse(UUID accountId, UUID customerId, long availablePoints, long reservedPoints,
        long lifetimeEarnedPoints, long lifetimeRedeemedPoints, long version) {
}

