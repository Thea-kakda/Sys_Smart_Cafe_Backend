package com.smartcoffee.modules.promotion.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.promotion.domain.PromotionRedemption;
import com.smartcoffee.modules.promotion.domain.RedemptionStatus;
import com.smartcoffee.modules.promotion.persistence.PromotionRedemptionRepository;
import com.smartcoffee.modules.promotion.persistence.PromotionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase B2 §9/§10 transition contract: redeem once, release/expire once with a
 * single usage decrement, replays are no-ops and a REDEEMED grant is final.
 */
class PromotionRedemptionLifecycleServiceTest {

    private PromotionRedemptionRepository redemptions;
    private PromotionRepository promotions;
    private AuditWriter audit;
    private OutboxEventWriter outbox;
    private PromotionRedemptionLifecycleService service;

    private final UUID order = UUID.randomUUID();
    private final UUID promotion = UUID.randomUUID();

    @BeforeEach
    void setup() {
        redemptions = mock(PromotionRedemptionRepository.class);
        promotions = mock(PromotionRepository.class);
        audit = mock(AuditWriter.class);
        outbox = mock(OutboxEventWriter.class);
        service = new PromotionRedemptionLifecycleService(redemptions, promotions, audit, outbox,
                JsonMapper.builder().build());
        when(redemptions.lockByOrderId(order)).thenAnswer(invocation -> List.of(row));
    }

    private PromotionRedemption row;

    private void reserved() {
        row = PromotionRedemption.reserve(promotion, UUID.randomUUID(), order, UUID.randomUUID(),
                new BigDecimal("2.00"), new BigDecimal("10.00"), "USD", Instant.now().plusSeconds(600), "WELCOME10",
                "Welcome 10%", "PERCENTAGE", new BigDecimal("10"), "key", "corr");
    }

    @Test
    void settlementRedeemsAReservedGrantOnce() {
        reserved();

        assertTrue(service.redeemForOrder(order, UUID.randomUUID()));

        assertEquals(RedemptionStatus.REDEEMED, row.status());
        assertNotNull(row.redeemedAt());
        verify(promotions, never()).releaseClaim(any());
        verify(audit).success(any(), any(), eq("PROMOTION_REDEEMED"), eq("PROMOTION"), eq("PROMOTION_REDEMPTION"),
                any(), any(), any(), isNull());
        verify(outbox).append(any(), eq("promotion.redeemed.v1"));
    }

    @Test
    void settlementReplayDoesNotRedeemAgain() {
        reserved();
        row.redeem();

        assertFalse(service.redeemForOrder(order, UUID.randomUUID()));

        verifyNoInteractions(audit, outbox);
        verify(promotions, never()).releaseClaim(any());
    }

    @Test
    void releasedGrantCanNoLongerBeRedeemed() {
        reserved();
        row.release("ORDER_CANCELLED");

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.redeemForOrder(order, UUID.randomUUID()));
        assertEquals("PROMOTION_RESERVATION_CONFLICT", conflict.code());
    }

    @Test
    void cancellationReleasesAndDecrementsUsageOnce() {
        reserved();

        assertTrue(service.releaseForOrder(order, "ORDER_CANCELLED", UUID.randomUUID()));

        assertEquals(RedemptionStatus.RELEASED, row.status());
        assertEquals("ORDER_CANCELLED", row.releaseReason());
        verify(promotions, times(1)).releaseClaim(promotion);
        verify(outbox).append(any(), eq("promotion.released.v1"));
    }

    @Test
    void repeatedReleaseDoesNotDecrementTwice() {
        reserved();
        service.releaseForOrder(order, "ORDER_CANCELLED", UUID.randomUUID());

        assertFalse(service.releaseForOrder(order, "ORDER_CANCELLED", UUID.randomUUID()));

        verify(promotions, times(1)).releaseClaim(promotion);
        verify(outbox, times(1)).append(any(), eq("promotion.released.v1"));
    }

    @Test
    void redeemedGrantIsNeverReleased() {
        reserved();
        row.redeem();

        assertFalse(service.releaseForOrder(order, "ORDER_CANCELLED", UUID.randomUUID()));

        assertEquals(RedemptionStatus.REDEEMED, row.status());
        verify(promotions, never()).releaseClaim(any());
    }

    @Test
    void expiryExpiresAndDecrementsUsageOnce() {
        reserved();

        assertTrue(service.expireForOrder(order, "PAYMENT_EXPIRED", null));

        assertEquals(RedemptionStatus.EXPIRED, row.status());
        assertEquals("PAYMENT_EXPIRED", row.releaseReason());
        verify(promotions, times(1)).releaseClaim(promotion);
        verify(audit).system(any(), eq("PROMOTION_EXPIRED"), eq("PROMOTION"), eq("PROMOTION_REDEMPTION"),
                any(), any(), any(), eq("PAYMENT_EXPIRED"));
        verify(outbox).append(any(), eq("promotion.expired.v1"));
    }

    @Test
    void expiryReplayDoesNotReleaseTwice() {
        reserved();
        service.expireForOrder(order, "PAYMENT_EXPIRED", null);

        assertFalse(service.expireForOrder(order, "PAYMENT_EXPIRED", null));

        verify(promotions, times(1)).releaseClaim(promotion);
        verify(outbox, times(1)).append(any(), eq("promotion.expired.v1"));
    }

    @Test
    void redeemedGrantIsNeverExpired() {
        reserved();
        row.redeem();

        assertFalse(service.expireForOrder(order, "PAYMENT_EXPIRED", null));

        assertEquals(RedemptionStatus.REDEEMED, row.status());
        verify(promotions, never()).releaseClaim(any());
        verifyNoInteractions(audit, outbox);
    }
}
