package com.smartcoffee.modules.promotion.application;

import com.smartcoffee.modules.promotion.domain.*;
import com.smartcoffee.modules.promotion.persistence.*;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single authoritative promotion evaluation engine (Phase B1).
 *
 * <p>Eligibility order is fixed and every rejection returns exactly one stable
 * {@link PromotionReasonCode}: existence, status, start-inclusive end-exclusive
 * window, store, currency, customer, configuration, eligible lines (product OR
 * category; no targets means every line), positive eligible subtotal, minimum on
 * that subtotal, global capacity, per-customer capacity, then discount over the
 * eligible subtotal only, with cap, clamp and 4-decimal HALF_UP rounding.
 *
 * <p>Read-only by contract: it never writes a redemption and never increments
 * usage_count. Capacity reported here is advisory until Phase B2 reserves
 * atomically during checkout.
 */
@Service
public class PromotionQuoteService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final List<RedemptionStatus> COUNTED_REDEMPTION_STATUSES =
            List.of(RedemptionStatus.RESERVED, RedemptionStatus.REDEEMED);

    private final PromotionRepository promotions;
    private final PromotionAssignmentRepository assignments;
    private final PromotionRedemptionRepository redemptions;

    public PromotionQuoteService(PromotionRepository promotions, PromotionAssignmentRepository assignments,
            PromotionRedemptionRepository redemptions) {
        this.promotions = promotions;
        this.assignments = assignments;
        this.redemptions = redemptions;
    }

    /**
     * Quotes an explicitly requested code. A blank code never falls back to
     * automatic promotion discovery: no public caller may enumerate automatic
     * campaigns through a null-code contract (Phase B1 §8.10).
     */
    @Transactional(readOnly = true)
    public PromotionQuote quote(PromotionQuoteContext context, String promotionCode) {
        if (promotionCode == null || promotionCode.isBlank())
            return rejected(null, null, null, 0L, context, PromotionReasonCode.PROMOTION_NOT_FOUND, ZERO);
        String code = promotionCode.trim().toUpperCase(Locale.ROOT);
        return promotions.findByCode(code)
                .map(p -> evaluate(p, context))
                .orElseGet(() -> rejected(null, code, null, 0L, context,
                        PromotionReasonCode.PROMOTION_NOT_FOUND, ZERO));
    }

    /**
     * Automatic resolution for later checkout work (Phase B2). Kept available so
     * the engine, not a second implementation, owns automatic evaluation; no
     * Phase B1 endpoint exposes it, and an explicit code never merges with it.
     */
    @Transactional(readOnly = true)
    public PromotionQuote automaticQuote(PromotionQuoteContext context) {
        for (Promotion p : promotions.automatic(Instant.now())) {
            PromotionQuote quote = evaluate(p, context);
            if (quote.eligible())
                return quote;
        }
        return rejected(null, null, null, 0L, context, PromotionReasonCode.PROMOTION_NOT_FOUND, ZERO);
    }

    /**
     * Evaluation of an already-locked promotion (Phase B2 checkout). The caller
     * holds the promotion row lock, so the capacity checks it performs cannot
     * race with another reservation between evaluation and claim.
     */
    public PromotionQuote evaluate(Promotion p, PromotionQuoteContext context) {
        Instant now = Instant.now();
        if (p.status() != PromotionStatus.ACTIVE)
            return reject(p, context, PromotionReasonCode.PROMOTION_NOT_ACTIVE, ZERO);
        if (now.isBefore(p.startsAt()))
            return reject(p, context, PromotionReasonCode.PROMOTION_NOT_STARTED, ZERO);
        if (!now.isBefore(p.endsAt()))
            return reject(p, context, PromotionReasonCode.PROMOTION_EXPIRED, ZERO);
        Targets targets = targets(p.id());
        if (!targets.stores().isEmpty() && !targets.stores().contains(context.storeId()))
            return reject(p, context, PromotionReasonCode.PROMOTION_STORE_NOT_ELIGIBLE, ZERO);
        if (p.currency() != null && !p.currency().equals(context.currencyCode()))
            return reject(p, context, PromotionReasonCode.PROMOTION_CURRENCY_MISMATCH, ZERO);
        if (p.perCustomerLimit() != null && context.customerId() == null)
            return reject(p, context, PromotionReasonCode.PROMOTION_CUSTOMER_NOT_ELIGIBLE, ZERO);
        if (configurationProblem(p))
            return reject(p, context, PromotionReasonCode.PROMOTION_INVALID_CONFIGURATION, ZERO);

        List<PromotionQuoteLine> eligibleLines = eligibleLines(context, targets);
        BigDecimal eligibleSubtotal = eligibleLines.stream()
                .map(PromotionQuoteLine::authoritativeLineSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);
        List<UUID> affected = eligibleLines.stream()
                .map(PromotionQuoteLine::cartItemId)
                .filter(Objects::nonNull)
                .toList();

        if (eligibleLines.isEmpty() || eligibleSubtotal.signum() <= 0)
            return reject(p, context, PromotionReasonCode.PROMOTION_NO_ELIGIBLE_ITEMS, ZERO);
        if (eligibleSubtotal.compareTo(p.minimumOrderAmount()) < 0)
            return reject(p, context, PromotionReasonCode.PROMOTION_MINIMUM_NOT_MET, eligibleSubtotal);
        if (p.usageLimit() != null && p.usageCount() >= p.usageLimit())
            return reject(p, context, PromotionReasonCode.PROMOTION_USAGE_LIMIT_REACHED, eligibleSubtotal);
        if (p.perCustomerLimit() != null) {
            long used = redemptions.countByPromotionIdAndCustomerIdAndStatusIn(p.id(), context.customerId(),
                    COUNTED_REDEMPTION_STATUSES);
            if (used >= p.perCustomerLimit())
                return reject(p, context, PromotionReasonCode.PROMOTION_CUSTOMER_LIMIT_REACHED, eligibleSubtotal);
        }

        // Promotion.discount owns percentage/fixed selection, the maximum cap,
        // clamping to the base and the 4-decimal HALF_UP rounding convention.
        BigDecimal discount = p.discount(eligibleSubtotal);
        return new PromotionQuote(p.id(), p.code(), p.name(), true, null, eligibleSubtotal, discount,
                context.currencyCode(), affected, p.version());
    }

    private boolean configurationProblem(Promotion p) {
        if (p.discountValue() == null || p.discountValue().signum() <= 0)
            return true;
        if (p.discountType() == DiscountType.PERCENTAGE && p.discountValue().compareTo(HUNDRED) > 0)
            return true;
        if (p.minimumOrderAmount() == null || p.minimumOrderAmount().signum() < 0)
            return true;
        if (p.maximumDiscountAmount() != null && p.maximumDiscountAmount().signum() < 0)
            return true;
        boolean monetary = p.discountType() == DiscountType.FIXED_AMOUNT || p.maximumDiscountAmount() != null;
        if (monetary && (p.currency() == null || p.currency().isBlank()))
            return true;
        if (p.usageLimit() != null && p.usageLimit() <= 0)
            return true;
        if (p.perCustomerLimit() != null && p.perCustomerLimit() <= 0)
            return true;
        return p.usageLimit() != null && p.perCustomerLimit() != null && p.perCustomerLimit() > p.usageLimit();
    }

    private Targets targets(UUID promotionId) {
        Set<UUID> stores = new HashSet<>();
        for (var row : assignments.storesFor(promotionId))
            stores.add(row.getLinkId());
        Set<UUID> products = new HashSet<>();
        for (var row : assignments.productsFor(promotionId))
            products.add(row.getLinkId());
        Set<UUID> categories = new HashSet<>();
        for (var row : assignments.categoriesFor(promotionId))
            categories.add(row.getLinkId());
        return new Targets(stores, products, categories);
    }

    /**
     * Product OR category per line; when the promotion targets neither, every
     * otherwise-valid cart line is eligible (Phase B1 §4.8).
     */
    private List<PromotionQuoteLine> eligibleLines(PromotionQuoteContext context, Targets targets) {
        if (targets.products().isEmpty() && targets.categories().isEmpty())
            return context.lines();
        return context.lines().stream()
                .filter(line -> (line.productId() != null && targets.products().contains(line.productId()))
                        || (line.categoryId() != null && targets.categories().contains(line.categoryId())))
                .toList();
    }

    private PromotionQuote reject(Promotion p, PromotionQuoteContext context, PromotionReasonCode reason,
            BigDecimal eligibleSubtotal) {
        return rejected(p.id(), p.code(), p.name(), p.version(), context, reason, eligibleSubtotal);
    }

    private PromotionQuote rejected(UUID promotionId, String code, String name, long version,
            PromotionQuoteContext context, PromotionReasonCode reason, BigDecimal eligibleSubtotal) {
        return new PromotionQuote(promotionId, code, name, false, reason, eligibleSubtotal, ZERO,
                context.currencyCode(), List.of(), version);
    }

    private record Targets(Set<UUID> stores, Set<UUID> products, Set<UUID> categories) {
    }
}
