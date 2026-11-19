package com.smartcoffee.modules.promotion.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.promotion.domain.*;
import com.smartcoffee.modules.promotion.persistence.PromotionRedemptionRepository;
import com.smartcoffee.modules.promotion.persistence.PromotionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase B2 §5/§6 reservation contract: fresh evaluation, version guard, guarded
 * capacity claim, and the RESERVED redemption snapshot written with the order.
 */
class PromotionCheckoutReservationServiceTest {

    private PromotionQuoteService quotes;
    private PromotionRepository promotions;
    private PromotionRedemptionRepository redemptions;
    private AuditWriter audit;
    private OutboxEventWriter outbox;
    private PromotionCheckoutReservationService service;

    private final UUID store = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID cartItem = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final UUID category = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    private Promotion promotion;
    private PromotionQuote quote;
    private PromotionQuoteContext context;

    @BeforeEach
    void setup() {
        quotes = mock(PromotionQuoteService.class);
        promotions = mock(PromotionRepository.class);
        redemptions = mock(PromotionRedemptionRepository.class);
        audit = mock(AuditWriter.class);
        outbox = mock(OutboxEventWriter.class);
        service = new PromotionCheckoutReservationService(quotes, promotions, redemptions, audit, outbox,
                JsonMapper.builder().build());
        ReflectionTestUtils.setField(service, "em", mock(EntityManager.class));

        promotion = Promotion.create("WELCOME10", "Welcome 10%", DiscountType.PERCENTAGE, new BigDecimal("10"),
                new BigDecimal("5"), null, "USD", Instant.now().minusSeconds(60),
                Instant.now().plus(30, ChronoUnit.DAYS), PromotionStatus.ACTIVE, 1000, 1, false, false, 100);
        quote = new PromotionQuote(promotion.id(), "WELCOME10", "Welcome 10%", true, null, new BigDecimal("10.0000"),
                new BigDecimal("1.0000"), "USD", List.of(cartItem), promotion.version());
        context = new PromotionQuoteContext(store, customer, "USD",
                List.of(new PromotionQuoteLine(cartItem, product, category, null, 1, new BigDecimal("10"),
                        new BigDecimal("10"))));
        when(quotes.quote(context, "WELCOME10")).thenReturn(quote);
        when(quotes.evaluate(promotion, context)).thenReturn(quote);
        when(promotions.lock(promotion.id())).thenReturn(Optional.of(promotion));
        when(redemptions.findByOrderId(order)).thenReturn(List.of());
        when(redemptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void eligibleCodeClaimsUsageAndReturnsTheLockedSnapshot() {
        var grant = service.reserve(context, "WELCOME10");

        assertEquals(promotion.id(), grant.promotionId());
        assertEquals("WELCOME10", grant.code());
        assertEquals("PERCENTAGE", grant.type());
        assertEquals(0, new BigDecimal("10").compareTo(grant.value()));
        assertEquals(0, new BigDecimal("1.0000").compareTo(grant.discountAmount()));
        assertEquals(0, new BigDecimal("10.0000").compareTo(grant.eligibleSubtotal()));
        assertEquals(List.of(cartItem), grant.affectedCartItemIds());
        assertEquals(1, promotion.usageCount(), "usage is claimed inside the checkout transaction");
    }

    @Test
    void ineligibleCodeIsRejectedWithItsStableReason() {
        quote = new PromotionQuote(promotion.id(), "WELCOME10", "Welcome 10%", false,
                PromotionReasonCode.PROMOTION_MINIMUM_NOT_MET, new BigDecimal("3.0000"), BigDecimal.ZERO, "USD",
                List.of(), promotion.version());
        when(quotes.quote(context, "WELCOME10")).thenReturn(quote);

        ConflictException rejection = assertThrows(ConflictException.class,
                () -> service.reserve(context, "WELCOME10"));
        assertEquals("PROMOTION_MINIMUM_NOT_MET", rejection.code());
        assertEquals(0, promotion.usageCount());
        verify(promotions, never()).lock(any());
    }

    @Test
    void definitionChangedSinceTheQuoteRequiresRevalidation() {
        quote = new PromotionQuote(promotion.id(), "WELCOME10", "Welcome 10%", true, null, new BigDecimal("10"),
                new BigDecimal("1"), "USD", List.of(cartItem), promotion.version() + 5);
        when(quotes.quote(context, "WELCOME10")).thenReturn(quote);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.reserve(context, "WELCOME10"));
        assertEquals("PROMOTION_REQUIRES_REVALIDATION", conflict.code());
        assertEquals(0, promotion.usageCount());
    }

    @Test
    void exhaustedGlobalCapacityIsRejectedUnderTheLock() {
        promotion.reserveUsage(); // usage_count = 1, limit = 1000 in setup; no throw
        promotion = Promotion.create("WELCOME10", "Welcome 10%", DiscountType.PERCENTAGE, new BigDecimal("10"),
                new BigDecimal("5"), null, "USD", Instant.now().minusSeconds(60),
                Instant.now().plus(30, ChronoUnit.DAYS), PromotionStatus.ACTIVE, 1, 1, false, false, 100);
        promotion.reserveUsage(); // exhausted by a concurrent winner
        when(promotions.lock(promotion.id())).thenReturn(Optional.of(promotion));
        quote = new PromotionQuote(promotion.id(), "WELCOME10", "Welcome 10%", true, null, new BigDecimal("10"),
                new BigDecimal("1"), "USD", List.of(cartItem), promotion.version());
        when(quotes.quote(context, "WELCOME10")).thenReturn(quote);
        when(quotes.evaluate(promotion, context)).thenReturn(quote);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.reserve(context, "WELCOME10"));
        assertEquals("PROMOTION_USAGE_LIMIT_REACHED", conflict.code());
        assertEquals(1, promotion.usageCount());
    }

    @Test
    void perCustomerLimitIsRecheckedUnderTheLock() {
        when(redemptions.countByPromotionIdAndCustomerIdAndStatusIn(eq(promotion.id()), eq(customer), any()))
                .thenReturn(1L);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.reserve(context, "WELCOME10"));
        assertEquals("PROMOTION_CUSTOMER_LIMIT_REACHED", conflict.code());
        assertEquals(0, promotion.usageCount());
    }

    @Test
    void recordsOneReservedRedemptionWithSnapshotAndSingleEvents() {
        var grant = service.reserve(context, "WELCOME10");
        Instant expires = Instant.now().plusSeconds(900);
        UUID key = UUID.randomUUID();

        var redemption = service.record(grant, order, customer, store, expires, UUID.randomUUID(), key);

        assertEquals(RedemptionStatus.RESERVED, redemption.status());
        assertEquals(promotion.id(), redemption.promotionId());
        assertEquals(order, redemption.orderId());
        assertEquals("WELCOME10", redemption.promotionCode());
        assertEquals("Welcome 10%", redemption.promotionName());
        assertEquals("PERCENTAGE", redemption.promotionType());
        assertEquals(0, new BigDecimal("10").compareTo(redemption.promotionValue()));
        assertEquals(0, new BigDecimal("10.0000").compareTo(redemption.eligibleSubtotal()));
        assertEquals(0, new BigDecimal("1.0000").compareTo(redemption.discountAmount()));
        assertEquals(expires, redemption.expiresAt());
        assertEquals(key.toString(), redemption.idempotencyKey());
        verify(redemptions, times(1)).save(any());
        verify(audit, times(1)).success(any(), eq(store), eq("PROMOTION_RESERVED"), eq("PROMOTION"),
                eq("PROMOTION_REDEMPTION"), any(), any(), any(), isNull());
        verify(outbox, times(1)).append(any(), eq("promotion.reserved.v1"));
    }

    @Test
    void recordingTheSamePromotionIsIdempotent() {
        var grant = service.reserve(context, "WELCOME10");
        var existing = PromotionRedemption.reserve(promotion.id(), customer, order, store, new BigDecimal("1"),
                new BigDecimal("10"), "USD", Instant.now().plusSeconds(900), "WELCOME10", "Welcome 10%", "PERCENTAGE",
                new BigDecimal("10"), "key", null);
        when(redemptions.findByOrderId(order)).thenReturn(List.of(existing));

        var redemption = service.record(grant, order, customer, store, Instant.now().plusSeconds(900),
                UUID.randomUUID(), UUID.randomUUID());

        assertSame(existing, redemption);
        verify(redemptions, never()).save(any());
        verifyNoInteractions(audit, outbox);
    }

    @Test
    void aDifferentPromotionForTheSameOrderIsAConflict() {
        var grant = service.reserve(context, "WELCOME10");
        var existing = PromotionRedemption.reserve(UUID.randomUUID(), customer, order, store, new BigDecimal("1"),
                new BigDecimal("10"), "USD", Instant.now().plusSeconds(900), "OTHER", "Other", "FIXED_AMOUNT",
                new BigDecimal("1"), "key", null);
        when(redemptions.findByOrderId(order)).thenReturn(List.of(existing));

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.record(grant, order, customer, store, Instant.now().plusSeconds(900),
                        UUID.randomUUID(), UUID.randomUUID()));
        assertEquals("PROMOTION_RESERVATION_CONFLICT", conflict.code());
        verify(redemptions, never()).save(any());
    }
}
