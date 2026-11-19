package com.smartcoffee.modules.cart.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Customer-facing promotion quote request (Phase B1 §7). Only the store selector
 * and the code are accepted; any money, customer or eligibility field a client
 * tries to inject is ignored by contract and recomputed from the owned cart.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CartPromotionQuoteRequest(@NotNull UUID storeId, @NotBlank String promotionCode) {
}
