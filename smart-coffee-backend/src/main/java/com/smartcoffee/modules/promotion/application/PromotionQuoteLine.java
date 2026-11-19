package com.smartcoffee.modules.promotion.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One authoritative cart line for promotion evaluation. Every value is a
 * server-owned cart snapshot (Phase B1 §3); public clients never supply any of
 * these fields.
 */
public record PromotionQuoteLine(UUID cartItemId, UUID productId, UUID categoryId, UUID variantId, int quantity,
        BigDecimal authoritativeUnitPrice, BigDecimal authoritativeLineSubtotal) {
}
