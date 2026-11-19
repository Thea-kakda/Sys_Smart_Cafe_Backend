package com.smartcoffee.modules.cart.api;

import java.math.BigDecimal;
import java.util.*;

public record CartItemResponse(UUID cartItemId, UUID productId, String productCode, String productName, int quantity,
        BigDecimal unitBasePrice, BigDecimal unitCustomizationPrice, BigDecimal lineTotal, String note,
        List<CartCustomizationResponse> customizations,
        // Slice 4B.1: additive variant identity read from the line's snapshot, so
        // a reloaded cart never depends on a live catalog join. Null for
        // product-level lines; existing fields are untouched for old clients.
        UUID variantId, String variantCode, String variantName, BigDecimal variantUnitPrice) {
}

