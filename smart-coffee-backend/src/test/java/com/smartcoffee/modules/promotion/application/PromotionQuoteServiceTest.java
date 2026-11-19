package com.smartcoffee.modules.promotion.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartcoffee.modules.promotion.domain.*;
import com.smartcoffee.modules.promotion.persistence.PromotionAssignmentRepository;
import com.smartcoffee.modules.promotion.persistence.PromotionAssignmentRepository.PromotionLinkRow;
import com.smartcoffee.modules.promotion.persistence.PromotionRedemptionRepository;
import com.smartcoffee.modules.promotion.persistence.PromotionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Phase B1 unit suite for the single promotion evaluation engine. Covers the
 * fixed evaluation order, product OR category targeting, eligible-line discount
 * base, minimum on that base, cap/clamp/rounding, capacity reads, stable reason
 * codes and input immutability. Time boundaries use real instants either side of
 * evaluation; no clock is faked.
 */
class PromotionQuoteServiceTest {
    private final PromotionRepository promotions = mock(PromotionRepository.class);
    private final PromotionAssignmentRepository assignments = mock(PromotionAssignmentRepository.class);
    private final PromotionRedemptionRepository redemptions = mock(PromotionRedemptionRepository.class);
    private final PromotionQuoteService service = new PromotionQuoteService(promotions, assignments, redemptions);

    private static final UUID STORE = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID P1 = UUID.randomUUID();
    private static final UUID P2 = UUID.randomUUID();
    private static final UUID P3 = UUID.randomUUID();
    private static final UUID C1 = UUID.randomUUID();
    private static final UUID C2 = UUID.randomUUID();
    private static final UUID C3 = UUID.randomUUID();

    // ---------------------------------------------------------------- discount

    @Test
    void percentageDiscountAppliesToEligibleSubtotal() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of());
        PromotionQuote q = service.quote(ctx(line("50.0000")), p.code());

        assertTrue(q.eligible());
        assertNull(q.reasonCode());
        assertEquals(dec("50.0000"), q.eligibleSubtotal());
        assertEquals(dec("5.0000"), q.discountAmount());
        assertEquals("USD", q.currencyCode());
        assertEquals(1, q.affectedCartItemIds().size());
    }

    @Test
    void fixedAmountDiscountAppliesDirectly() {
        Promotion p = active(DiscountType.FIXED_AMOUNT, "7", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of());
        PromotionQuote q = service.quote(ctx(line("50.0000")), p.code());

        assertTrue(q.eligible());
        assertEquals(dec("7.0000"), q.discountAmount());
    }

    @Test
    void maximumDiscountCapWinsOverPercentage() {
        Promotion p = active(DiscountType.PERCENTAGE, "50", "0", "2", "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of());
        PromotionQuote q = service.quote(ctx(line("10.0000")), p.code());

        assertEquals(dec("2.0000"), q.discountAmount());
    }

    @Test
    void discountIsClampedToTheEligibleSubtotal() {
        Promotion p = active(DiscountType.FIXED_AMOUNT, "20", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of());
        PromotionQuote q = service.quote(ctx(line("10.0000")), p.code());

        assertEquals(dec("10.0000"), q.discountAmount(), "never discount more than the eligible base");
    }

    @Test
    void discountUsesFourDecimalHalfUpRounding() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of());
        PromotionQuote q = service.quote(ctx(line("12.3456")), p.code());

        assertEquals(new BigDecimal("1.2346"), q.discountAmount());
    }

    // ---------------------------------------------------------------- targeting

    @Test
    void storeTargetingRejectsAnotherStore() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of(UUID.randomUUID()));
        PromotionQuote q = service.quote(ctx(line("10.0000")), p.code());

        assertFalse(q.eligible());
        assertEquals(PromotionReasonCode.PROMOTION_STORE_NOT_ELIGIBLE, q.reasonCode());
    }

    @Test
    void storeTargetingAcceptsTheAuthoritativeStore() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of(STORE));
        assertTrue(service.quote(ctx(line("10.0000")), p.code()).eligible());
    }

    @Test
    void productOnlyTargetDiscountsOnlyTheTargetedLine() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(P1), List.of(), List.of());
        PromotionQuote q = service.quote(ctx(line(P1, C1, "10.0000"), line(P2, C2, "15.0000")), p.code());

        assertEquals(dec("10.0000"), q.eligibleSubtotal());
        assertEquals(dec("1.0000"), q.discountAmount());
        assertEquals(1, q.affectedCartItemIds().size());
    }

    @Test
    void categoryOnlyTargetDiscountsOnlyTheTargetedCategory() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(C1), List.of());
        PromotionQuote q = service.quote(ctx(line(P1, C1, "15.0000"), line(P2, C2, "20.0000")), p.code());

        assertEquals(dec("15.0000"), q.eligibleSubtotal());
        assertEquals(dec("1.5000"), q.discountAmount());
    }

    @Test
    void productOrCategoryTargetingMatchesEither() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(P1), List.of(C1), List.of());
        PromotionQuote q = service.quote(
                ctx(line(P1, C2, "10.0000"), line(P2, C1, "15.0000"), line(P3, C3, "20.0000")), p.code());

        assertEquals(dec("25.0000"), q.eligibleSubtotal(), "OR, not AND");
        assertEquals(dec("2.5000"), q.discountAmount());
        assertEquals(2, q.affectedCartItemIds().size());
    }

    @Test
    void targetsPresentButNoLineMatchesIsNoEligibleItems() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(P3), List.of(C3), List.of());
        PromotionQuote q = service.quote(ctx(line(P1, C1, "10.0000")), p.code());

        assertFalse(q.eligible());
        assertEquals(PromotionReasonCode.PROMOTION_NO_ELIGIBLE_ITEMS, q.reasonCode());
        assertEquals(dec("0.0000"), q.eligibleSubtotal());
        assertTrue(q.affectedCartItemIds().isEmpty());
    }

    @Test
    void noTargetsMakesEveryLineEligible() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of());
        PromotionQuote q = service.quote(
                ctx(line(P1, C1, "10.0000"), line(P2, C2, "15.0000"), line(P3, C3, "20.0000")), p.code());

        assertEquals(dec("45.0000"), q.eligibleSubtotal());
        assertEquals(3, q.affectedCartItemIds().size());
    }

    @Test
    void emptyCartHasNoEligibleItems() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of());
        PromotionQuote q = service.quote(ctx(), p.code());

        assertEquals(PromotionReasonCode.PROMOTION_NO_ELIGIBLE_ITEMS, q.reasonCode());
    }

    // ---------------------------------------------------------------- minimum

    @Test
    void minimumIsEvaluatedAgainstEligibleSubtotalOnly() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "20", null, "USD", null, null);
        stubLinks(p, List.of(P1), List.of(), List.of());
        PromotionQuote q = service.quote(ctx(line(P1, C1, "10.0000"), line(P2, C2, "40.0000")), p.code());

        assertFalse(q.eligible());
        assertEquals(PromotionReasonCode.PROMOTION_MINIMUM_NOT_MET, q.reasonCode());
        assertEquals(dec("10.0000"), q.eligibleSubtotal());
    }

    @Test
    void minimumMetByEligibleSubtotalAllowsDiscount() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "10", null, "USD", null, null);
        stubLinks(p, List.of(P1), List.of(), List.of());
        assertTrue(service.quote(ctx(line(P1, C1, "10.0000"), line(P2, C2, "40.0000")), p.code()).eligible());
    }

    // --------------------------------------------------------------- currency

    @Test
    void currencyMismatchIsRejected() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(), List.of(), List.of());
        PromotionQuote q = service.quote(ctx("EUR", line("10.0000")), p.code());

        assertEquals(PromotionReasonCode.PROMOTION_CURRENCY_MISMATCH, q.reasonCode());
    }

    // ------------------------------------------------------- status and window

    @Test
    void nonActiveStatusIsRejected() {
        Promotion p = Promotion.create("SAVE10", "Save", DiscountType.PERCENTAGE, BigDecimal.TEN,
                BigDecimal.ZERO, null, "USD", Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS), PromotionStatus.PAUSED, null, null, false, false, 0);
        stubLinks(p, List.of(), List.of(), List.of());

        assertEquals(PromotionReasonCode.PROMOTION_NOT_ACTIVE,
                service.quote(ctx(line("10.0000")), p.code()).reasonCode());
    }

    @Test
    void futureStartIsNotStarted() {
        Promotion p = window(Instant.now().plus(1, ChronoUnit.HOURS), Instant.now().plus(2, ChronoUnit.HOURS));
        stubLinks(p, List.of(), List.of(), List.of());

        assertEquals(PromotionReasonCode.PROMOTION_NOT_STARTED,
                service.quote(ctx(line("10.0000")), p.code()).reasonCode());
    }

    @Test
    void elapsedEndIsExpired() {
        Promotion p = window(Instant.now().minus(2, ChronoUnit.HOURS), Instant.now().minus(1, ChronoUnit.HOURS));
        stubLinks(p, List.of(), List.of(), List.of());

        assertEquals(PromotionReasonCode.PROMOTION_EXPIRED,
                service.quote(ctx(line("10.0000")), p.code()).reasonCode());
    }

    @Test
    void startBoundaryIsInclusiveAndEndBoundaryIsExclusive() {
        Promotion atStart = window(Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS));
        stubLinks(atStart, List.of(), List.of(), List.of());
        assertTrue(service.quote(ctx(line("10.0000")), atStart.code()).eligible(), "start is inclusive");

        Promotion atEnd = window(Instant.now().minus(1, ChronoUnit.HOURS), Instant.now());
        stubLinks(atEnd, List.of(), List.of(), List.of());
        assertEquals(PromotionReasonCode.PROMOTION_EXPIRED,
                service.quote(ctx(line("10.0000")), atEnd.code()).reasonCode(), "end is exclusive");
    }

    // -------------------------------------------------------------- capacities

    @Test
    void globalUsageLimitExhausted() {
        Promotion real = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", 5, null);
        Promotion p = spy(real);
        doReturn(5).when(p).usageCount();
        doReturn(5).when(p).usageLimit();
        stubLinks(p, List.of(), List.of(), List.of());

        assertEquals(PromotionReasonCode.PROMOTION_USAGE_LIMIT_REACHED,
                service.quote(ctx(line("10.0000")), p.code()).reasonCode());
    }

    @Test
    void globalCapacityRemainingAllowsQuote() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", 5, null);
        stubLinks(p, List.of(), List.of(), List.of());
        assertTrue(service.quote(ctx(line("10.0000")), p.code()).eligible());
    }

    @Test
    void perCustomerLimitCountsOnlyReservedAndRedeemed() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", 100, 1);
        stubLinks(p, List.of(), List.of(), List.of());
        when(redemptions.countByPromotionIdAndCustomerIdAndStatusIn(eq(p.id()), eq(CUSTOMER), anyList()))
                .thenReturn(0L);
        assertTrue(service.quote(ctx(line("10.0000")), p.code()).eligible());

        when(redemptions.countByPromotionIdAndCustomerIdAndStatusIn(eq(p.id()), eq(CUSTOMER), anyList()))
                .thenReturn(1L);
        assertEquals(PromotionReasonCode.PROMOTION_CUSTOMER_LIMIT_REACHED,
                service.quote(ctx(line("10.0000")), p.code()).reasonCode());
        verify(redemptions, times(2)).countByPromotionIdAndCustomerIdAndStatusIn(p.id(), CUSTOMER,
                List.of(RedemptionStatus.RESERVED, RedemptionStatus.REDEEMED));
    }

    @Test
    void guestIsRejectedWhenACustomerLimitIsConfigured() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", 100, 1);
        stubLinks(p, List.of(), List.of(), List.of());

        PromotionQuote q = service.quote(
                new PromotionQuoteContext(STORE, null, "USD", List.of(line("10.0000"))), p.code());
        assertEquals(PromotionReasonCode.PROMOTION_CUSTOMER_NOT_ELIGIBLE, q.reasonCode());
    }

    // -------------------------------------------------------- invalid config

    @Test
    void invalidConfigurationIsRejectedBeforeCalculation() {
        Promotion tooMuch = active(DiscountType.PERCENTAGE, "150", "0", null, "USD", null, null);
        stubLinks(tooMuch, List.of(), List.of(), List.of());
        assertEquals(PromotionReasonCode.PROMOTION_INVALID_CONFIGURATION,
                service.quote(ctx(line("10.0000")), tooMuch.code()).reasonCode());

        Promotion noCurrency = active(DiscountType.FIXED_AMOUNT, "5", "0", null, null, null, null);
        stubLinks(noCurrency, List.of(), List.of(), List.of());
        assertEquals(PromotionReasonCode.PROMOTION_INVALID_CONFIGURATION,
                service.quote(ctx(line("10.0000")), noCurrency.code()).reasonCode());

        Promotion invertedLimits = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", 1, 2);
        stubLinks(invertedLimits, List.of(), List.of(), List.of());
        assertEquals(PromotionReasonCode.PROMOTION_INVALID_CONFIGURATION,
                service.quote(ctx(line("10.0000")), invertedLimits.code()).reasonCode());
    }

    // ----------------------------------------------------- explicit vs auto

    @Test
    void unknownOrBlankCodeIsNotFoundAndNeverEnumeratesAutomaticPromotions() {
        when(promotions.findByCode(anyString())).thenReturn(Optional.empty());

        assertEquals(PromotionReasonCode.PROMOTION_NOT_FOUND,
                service.quote(ctx(line("10.0000")), "NOPE").reasonCode());
        assertEquals(PromotionReasonCode.PROMOTION_NOT_FOUND,
                service.quote(ctx(line("10.0000")), "  ").reasonCode());
        assertEquals(PromotionReasonCode.PROMOTION_NOT_FOUND,
                service.quote(ctx(line("10.0000")), null).reasonCode());
        verify(promotions, never()).automatic(any());
    }

    @Test
    void automaticQuoteReturnsOnlyTheFirstEligiblePromotion() {
        Promotion tooSmall = Promotion.create("AUT1", "Auto 1", DiscountType.PERCENTAGE, BigDecimal.TEN,
                new BigDecimal("1000"), null, "USD", Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS), PromotionStatus.ACTIVE, null, null, false, true, 100);
        Promotion winner = Promotion.create("AUT2", "Auto 2", DiscountType.PERCENTAGE, BigDecimal.TEN,
                BigDecimal.ZERO, null, "USD", Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS), PromotionStatus.ACTIVE, null, null, false, true, 50);
        when(promotions.automatic(any())).thenReturn(List.of(tooSmall, winner));
        stubLinks(tooSmall, List.of(), List.of(), List.of());
        stubLinks(winner, List.of(), List.of(), List.of());

        PromotionQuote q = service.automaticQuote(ctx(line("50.0000")));
        assertTrue(q.eligible());
        assertEquals(winner.id(), q.promotionId(), "one promotion only; no stacking");
    }

    // ------------------------------------------------------------- immutability

    @Test
    void inputLinesAreNeverMutated() {
        Promotion p = active(DiscountType.PERCENTAGE, "10", "0", null, "USD", null, null);
        stubLinks(p, List.of(P1), List.of(), List.of());
        PromotionQuoteLine targeted = line(P1, C1, "10.0000");
        PromotionQuoteLine other = line(P2, C2, "15.0000");
        List<PromotionQuoteLine> lines = new ArrayList<>(List.of(targeted, other));
        PromotionQuoteContext context = new PromotionQuoteContext(STORE, CUSTOMER, "USD", lines);

        service.quote(context, p.code());

        assertEquals(List.of(targeted, other), lines);
        assertSame(targeted, context.lines().get(0));
        assertEquals(2, context.lines().size());
    }

    // ---------------------------------------------------------------- helpers

    private static Promotion active(DiscountType type, String value, String minimum, String maximum, String currency,
            Integer usageLimit, Integer perCustomerLimit) {
        return Promotion.create("SAVE10", "Save 10", type, dec(value), dec(minimum),
                maximum == null ? null : dec(maximum), currency, Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS), PromotionStatus.ACTIVE, usageLimit, perCustomerLimit, false,
                false, 0);
    }

    private static Promotion window(Instant start, Instant end) {
        return Promotion.create("SAVE10", "Save 10", DiscountType.PERCENTAGE, BigDecimal.TEN, BigDecimal.ZERO, null,
                "USD", start, end, PromotionStatus.ACTIVE, null, null, false, false, 0);
    }

    private void stubLinks(Promotion p, List<UUID> products, List<UUID> categories, List<UUID> stores) {
        UUID id = p.id();
        when(promotions.findByCode(p.code())).thenReturn(Optional.of(p));
        when(assignments.productsFor(id)).thenReturn(rows(id, products));
        when(assignments.categoriesFor(id)).thenReturn(rows(id, categories));
        when(assignments.storesFor(id)).thenReturn(rows(id, stores));
    }

    private static List<PromotionLinkRow> rows(UUID promotionId, List<UUID> links) {
        return links.stream().map(l -> link(promotionId, l)).toList();
    }

    private static PromotionLinkRow link(UUID promotionId, UUID linkId) {
        return new PromotionLinkRow() {
            @Override
            public UUID getPromotionId() {
                return promotionId;
            }

            @Override
            public UUID getLinkId() {
                return linkId;
            }
        };
    }

    private static PromotionQuoteContext ctx(PromotionQuoteLine... lines) {
        return ctx("USD", lines);
    }

    private static PromotionQuoteContext ctx(String currency, PromotionQuoteLine... lines) {
        return new PromotionQuoteContext(STORE, CUSTOMER, currency, List.of(lines));
    }

    private static PromotionQuoteLine line(String subtotal) {
        return line(UUID.randomUUID(), UUID.randomUUID(), subtotal);
    }

    private static PromotionQuoteLine line(UUID productId, UUID categoryId, String subtotal) {
        return new PromotionQuoteLine(UUID.randomUUID(), productId, categoryId, null, 1, dec(subtotal),
                dec(subtotal));
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}
