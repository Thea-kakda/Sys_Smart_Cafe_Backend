package com.smartcoffee.modules.promotion.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.infrastructure.messaging.EventEnvelope;
import com.smartcoffee.infrastructure.messaging.EventTypes;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.promotion.domain.PromotionRedemption;
import com.smartcoffee.modules.promotion.domain.RedemptionStatus;
import com.smartcoffee.modules.promotion.persistence.PromotionRedemptionRepository;
import com.smartcoffee.modules.promotion.persistence.PromotionRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The one place a RESERVED Promotion becomes REDEEMED, RELEASED or EXPIRED
 * (Phase B2 §9, §10).
 *
 * <p>Every transition locks the order's redemption row, is guarded by the
 * current status and therefore happens exactly once: a settlement or expiry
 * replay is a no-op, and usage_count is decremented only in the same
 * transaction as a RESERVED→RELEASED/EXPIRED transition, never twice and never
 * below zero. A REDEEMED redemption is never released in this phase.
 *
 * <p>All methods require the caller's transaction ({@code MANDATORY}), so the
 * transition, the usage change, the audit row and the outbox event commit (or
 * roll back) with the order/payment state that caused them.
 */
@Service
public class PromotionRedemptionLifecycleService {
    private final PromotionRedemptionRepository redemptions;
    private final PromotionRepository promotions;
    private final AuditWriter audit;
    private final OutboxEventWriter outbox;
    private final ObjectMapper json;

    public PromotionRedemptionLifecycleService(PromotionRedemptionRepository redemptions, PromotionRepository promotions,
            AuditWriter audit, OutboxEventWriter outbox, ObjectMapper json) {
        this.redemptions = redemptions;
        this.promotions = promotions;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * Settlement: RESERVED → REDEEMED once. An already-redeemed row is a
     * replay no-op; a released/expired row can no longer be redeemed and is a
     * stable conflict, never a silent full-price settlement.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean redeemForOrder(UUID orderId, UUID actor) {
        boolean changed = false;
        for (PromotionRedemption row : redemptions.lockByOrderId(orderId)) {
            if (row.status() == RedemptionStatus.REDEEMED)
                continue;
            if (row.status() != RedemptionStatus.RESERVED)
                throw new ConflictException("PROMOTION_RESERVATION_CONFLICT",
                        "Promotion reservation is no longer redeemable");
            row.redeem();
            emit(row, EventTypes.PROMOTION_REDEEMED, "promotion.redeemed.v1", "PROMOTION_REDEEMED", actor, null);
            changed = true;
        }
        return changed;
    }

    /** Accepted cancellation: RESERVED → RELEASED, usage given back once. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseForOrder(UUID orderId, String reason, UUID actor) {
        return terminate(orderId, reason, actor, false);
    }

    /** Payment/order expiry: RESERVED → EXPIRED, usage given back once. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireForOrder(UUID orderId, String reason, UUID actor) {
        return terminate(orderId, reason, actor, true);
    }

    private boolean terminate(UUID orderId, String reason, UUID actor, boolean expired) {
        boolean changed = false;
        for (PromotionRedemption row : redemptions.lockByOrderId(orderId)) {
            if (row.status() != RedemptionStatus.RESERVED)
                continue; // REDEEMED is final in B2; RELEASED/EXPIRED are already terminal
            if (expired)
                row.expire(reason);
            else
                row.release(reason);
            promotions.releaseClaim(row.promotionId());
            emit(row, expired ? EventTypes.PROMOTION_EXPIRED : EventTypes.PROMOTION_RELEASED,
                    expired ? "promotion.expired.v1" : "promotion.released.v1",
                    expired ? "PROMOTION_EXPIRED" : "PROMOTION_RELEASED", actor, reason);
            changed = true;
        }
        return changed;
    }

    private void emit(PromotionRedemption row, String eventType, String routingKey, String action, UUID actor,
            String reason) {
        Map<String, String> after = new java.util.LinkedHashMap<>();
        after.put("status", row.status().name());
        after.put("orderId", row.orderId().toString());
        after.put("promotionId", row.promotionId().toString());
        if (reason != null)
            after.put("reason", reason);
        if (actor == null)
            audit.system(row.storeId(), action, "PROMOTION", "PROMOTION_REDEMPTION", row.id(),
                    Map.of("status", RedemptionStatus.RESERVED.name()), after, reason);
        else
            audit.success(actor, row.storeId(), action, "PROMOTION", "PROMOTION_REDEMPTION", row.id(),
                    Map.of("status", RedemptionStatus.RESERVED.name()), after, reason);

        var payload = json.createObjectNode().put("redemptionId", row.id().toString())
                .put("promotionId", row.promotionId().toString()).put("orderId", row.orderId().toString())
                .put("storeId", row.storeId().toString()).put("status", row.status().name())
                .put("discountAmount", row.discountAmount().toPlainString()).put("currencyCode", row.currency());
        if (reason != null)
            payload.put("reason", reason);
        outbox.append(new EventEnvelope(UUID.randomUUID(), eventType, 1, "PROMOTION_REDEMPTION", row.id(),
                row.storeId(), Instant.now(), null, null, payload), routingKey);
    }
}
