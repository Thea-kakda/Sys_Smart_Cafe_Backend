package com.smartcoffee.modules.customer.api;

import java.time.LocalDate;
import java.util.UUID;

public record CustomerProfileResponse(
        UUID customerId,
        UUID userId,
        UUID preferredStoreId,
        String loyaltyNumber,
        LocalDate birthDate,
        boolean marketingOptIn) {}

