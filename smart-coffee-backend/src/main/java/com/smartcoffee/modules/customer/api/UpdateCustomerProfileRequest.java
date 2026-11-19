package com.smartcoffee.modules.customer.api;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateCustomerProfileRequest(
        UUID preferredStoreId,
        LocalDate birthDate,
        boolean marketingOptIn) {}

