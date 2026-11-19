package com.smartcoffee.modules.promotion.application;

import com.smartcoffee.modules.promotion.api.*;
import com.smartcoffee.modules.promotion.persistence.PromotionAssignmentRepository;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legacy adapter for {@code POST /api/v1/promotions/validate}.
 *
 * <p>The request is client-trusting (subtotal, customerId, product/category ids
 * all come from the caller), so it is retained only as a management diagnostic
 * and is gated by {@code promotion:read} at the controller. It is not the
 * customer contract: customer quoting goes through the server-owned cart at
 * {@code POST /api/v1/carts/current/promotion/quote}. Behaviour is rebuilt on
 * {@link PromotionQuoteService} so there is exactly one eligibility engine; the
 * request's category ids and each product's real category are both honoured.
 */
@Service
public class PromotionEligibilityService {
    private final PromotionQuoteService quotes;
    private final PromotionAssignmentRepository assignments;

    public PromotionEligibilityService(PromotionQuoteService quotes, PromotionAssignmentRepository assignments) {
        this.quotes = quotes;
        this.assignments = assignments;
    }

    @Transactional(readOnly = true)
    public PromotionResponse validate(PromotionValidationRequest q) {
        PromotionQuote quote = quotes.quote(context(q), q.promotionCode());
        return new PromotionResponse(quote.promotionId(), quote.code() != null ? quote.code() : q.promotionCode(),
                quote.name(), quote.eligible(), quote.discountAmount(), q.currencyCode(),
                quote.reasonCode() == null ? null : quote.reasonCode().name());
    }

    private PromotionQuoteContext context(PromotionValidationRequest q) {
        BigDecimal subtotal = q.subtotal() == null ? BigDecimal.ZERO : q.subtotal();
        List<UUID> distinctProducts = q.productIds() == null ? List.of() : q.productIds().stream().distinct().toList();
        if (!distinctProducts.isEmpty()) {
            UUID[] productIds = distinctProducts.toArray(UUID[]::new);
            Map<UUID, UUID> categories = new HashMap<>();
            for (var row : assignments.categoriesForProducts(productIds))
                categories.put(row.getProductId(), row.getCategoryId());
            BigDecimal[] amounts = split(subtotal, productIds.length);
            List<PromotionQuoteLine> lines = new ArrayList<>();
            for (int i = 0; i < productIds.length; i++)
                lines.add(new PromotionQuoteLine(null, productIds[i], categories.get(productIds[i]), null, 1,
                        amounts[i], amounts[i]));
            return new PromotionQuoteContext(q.storeId(), q.customerId(), q.currencyCode(), lines);
        }
        if (q.categoryIds() != null && !q.categoryIds().isEmpty()) {
            List<UUID> categoryIds = q.categoryIds().stream().distinct().toList();
            BigDecimal[] amounts = split(subtotal, categoryIds.size());
            List<PromotionQuoteLine> lines = new ArrayList<>();
            for (int i = 0; i < categoryIds.size(); i++)
                lines.add(new PromotionQuoteLine(null, null, categoryIds.get(i), null, 1, amounts[i], amounts[i]));
            return new PromotionQuoteContext(q.storeId(), q.customerId(), q.currencyCode(), lines);
        }
        return new PromotionQuoteContext(q.storeId(), q.customerId(), q.currencyCode(),
                List.of(new PromotionQuoteLine(null, null, null, null, 1, subtotal, subtotal)));
    }

    /** Splits a legacy scalar subtotal so the parts always sum back to it exactly. */
    private static BigDecimal[] split(BigDecimal total, int count) {
        BigDecimal per = total.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        BigDecimal[] parts = new BigDecimal[count];
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < count - 1; i++) {
            parts[i] = per;
            allocated = allocated.add(per);
        }
        parts[count - 1] = total.subtract(allocated).setScale(4, RoundingMode.HALF_UP);
        return parts;
    }
}
