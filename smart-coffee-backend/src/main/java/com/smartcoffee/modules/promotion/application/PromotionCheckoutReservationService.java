package com.smartcoffee.modules.promotion.application;

import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.infrastructure.messaging.EventEnvelope;
import com.smartcoffee.infrastructure.messaging.EventTypes;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.promotion.domain.*;
import com.smartcoffee.modules.promotion.persistence.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Atomic Promotion reservation for authoritative checkout (Phase B2 §5, §6).
 *
 * <p>The checkout transaction evaluates a fresh quote from its own rebuilt cart
 * context, then this service takes the promotion row lock and re-evaluates
 * against the locked definition: a definition that changed in between is
 * refused with {@code PROMOTION_REQUIRES_REVALIDATION} instead of silently
 * applying different rules. Usage is claimed with a guarded increment under
 * that lock, so the global and per-customer capacities can never be exceeded.
 *
 * <p>Both methods require an existing checkout transaction ({@code MANDATORY}):
 * a reservation can never commit without its Order, and a failed checkout
 * rolls back the usage increment, the redemption row, the audit and the outbox
 * event together.
 */
@Service
public class PromotionCheckoutReservationService {
    private static final List<RedemptionStatus> COUNTED = List.of(RedemptionStatus.RESERVED,
            RedemptionStatus.REDEEMED);

    private final PromotionQuoteService quotes;
    private final PromotionRepository promotions;
    private final PromotionRedemptionRepository redemptions;
    private final AuditWriter audit;
    private final OutboxEventWriter outbox;
    private final ObjectMapper json;
    @PersistenceContext
    private EntityManager em;

    public PromotionCheckoutReservationService(PromotionQuoteService quotes, PromotionRepository promotions,
            PromotionRedemptionRepository redemptions, AuditWriter audit, OutboxEventWriter outbox, ObjectMapper j) {
        this.quotes = quotes;
        this.promotions = promotions;
        this.redemptions = redemptions;
        this.audit = audit;
        this.outbox = outbox;
        json = j;
    }

    /** Definition snapshot of the grant, taken while the promotion is locked. */
    public record PromotionGrant(UUID promotionId, String code, String name, String type, BigDecimal value,
            BigDecimal discountAmount, BigDecimal eligibleSubtotal, String currency, List<UUID> affectedCartItemIds,
            long promotionVersion) {
        public PromotionGrant {
            affectedCartItemIds = affectedCartItemIds == null ? List.of() : List.copyOf(affectedCartItemIds);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PromotionGrant reserve(PromotionQuoteContext context, String promotionCode) {
        PromotionQuote quote = quotes.quote(context, promotionCode);
        if (!quote.eligible())
            throw rejection(quote.reasonCode());

        // The advisory quote already loaded the definition, so drop that instance:
        // the locking read below must hydrate committed state, not the cached copy.
        Promotion cached = em.find(Promotion.class, quote.promotionId());
        if (cached != null)
            em.detach(cached);
        Promotion locked = promotions.lock(quote.promotionId())
                .orElseThrow(() -> rejection(PromotionReasonCode.PROMOTION_NOT_FOUND));

        // Capacity is checked from the locked definition first, so a lost race is
        // reported as the stable limit code rather than as a stale version.
        PromotionQuote fresh = quotes.evaluate(locked, context);
        if (!fresh.eligible())
            throw rejection(fresh.reasonCode());
        if (locked.version() != quote.promotionVersion())
            throw new ConflictException("PROMOTION_REQUIRES_REVALIDATION",
                    "Promotion rules changed during checkout; refresh and retry");
        if (locked.perCustomerLimit() != null) {
            long used = redemptions.countByPromotionIdAndCustomerIdAndStatusIn(locked.id(), context.customerId(),
                    COUNTED);
            if (used >= locked.perCustomerLimit())
                throw rejection(PromotionReasonCode.PROMOTION_CUSTOMER_LIMIT_REACHED);
        }
        locked.reserveUsage();

        return new PromotionGrant(fresh.promotionId(), fresh.code(), fresh.name(),
                locked.discountType() == null ? null : locked.discountType().name(), locked.discountValue(),
                fresh.discountAmount(), fresh.eligibleSubtotal(), context.currencyCode(),
                fresh.affectedCartItemIds(), fresh.promotionVersion());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PromotionRedemption record(PromotionGrant grant, UUID orderId, UUID customerId, UUID storeId,
            Instant expiresAt, UUID actor, UUID idempotencyKey) {
        List<PromotionRedemption> rows = redemptions.findByOrderId(orderId);
        if (!rows.isEmpty()) {
            PromotionRedemption row = rows.get(0);
            if (row.promotionId().equals(grant.promotionId()))
                return row;
            throw new ConflictException(row.status() == RedemptionStatus.REDEEMED
                    ? "PROMOTION_ALREADY_REDEEMED"
                    : "PROMOTION_RESERVATION_CONFLICT",
                    "Order already owns a different promotion reservation");
        }

        var redemption = redemptions.save(PromotionRedemption.reserve(grant.promotionId(), customerId, orderId,
                storeId, grant.discountAmount(), grant.eligibleSubtotal(), grant.currency(), expiresAt, grant.code(),
                grant.name(), grant.type(), grant.value(), idempotencyKey == null ? null : idempotencyKey.toString(),
                correlation()));
        audit.success(actor, storeId, "PROMOTION_RESERVED", "PROMOTION", "PROMOTION_REDEMPTION", redemption.id(),
                Map.of(), Map.of("status", redemption.status().name(), "promotionId", grant.promotionId().toString(),
                        "orderId", orderId.toString(), "discountAmount", grant.discountAmount().toPlainString()),
                null);
        var payload = json.createObjectNode().put("redemptionId", redemption.id().toString())
                .put("promotionId", grant.promotionId().toString()).put("orderId", orderId.toString())
                .put("storeId", storeId.toString()).put("discountAmount", grant.discountAmount().toPlainString())
                .put("currencyCode", grant.currency());
        outbox.append(new EventEnvelope(UUID.randomUUID(), EventTypes.PROMOTION_RESERVED, 1, "ORDER", orderId, storeId,
                Instant.now(), null, null, payload), "promotion.reserved.v1");
        return redemption;
    }

    private ConflictException rejection(PromotionReasonCode reason) {
        return new ConflictException(reason.name(), "Promotion cannot be applied to this order");
    }

    private static String correlation() {
        var c = CorrelationContext.get();
        return c == null ? null : c.correlationId();
    }
}
