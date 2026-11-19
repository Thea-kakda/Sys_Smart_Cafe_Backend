package com.smartcoffee.modules.ordering.api;

import jakarta.validation.constraints.*;
import java.util.UUID;

/**
 * Checkout command (Phase B2 §4). The only promotion-related input a client may
 * send is the code itself: identity, price, discount, eligible lines, store,
 * customer and currency are all rebuilt authoritatively by checkout.
 */
public record CreateOrderRequest(@NotNull UUID cartId, @NotBlank String fulfillmentType,
        @Size(max = 500) String specialInstructions, @Size(max = 50) String promotionCode) {

    /** Compatibility for callers that checkout without a promotion. */
    public CreateOrderRequest(UUID cartId, String fulfillmentType, String specialInstructions) {
        this(cartId, fulfillmentType, specialInstructions, null);
    }
}
