package com.smartcoffee.modules.ordering.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.infrastructure.messaging.EventEnvelope;
import com.smartcoffee.infrastructure.messaging.EventTypes;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.application.InventoryReservationFinalizationService;
import com.smartcoffee.modules.inventory.application.ReservationReasons;
import com.smartcoffee.modules.ordering.api.CancelOrderRequest;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.domain.Order;
import com.smartcoffee.modules.ordering.domain.OrderStatus;
import com.smartcoffee.modules.ordering.persistence.OrderRepository;
import com.smartcoffee.modules.promotion.application.PromotionRedemptionLifecycleService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Administrative order state commands (Full-stack wiring §1.4). Explicit
 * domain commands, never a generic status PATCH: cancel (any live state) and
 * complete (READY only), both guarded by order:admin-write plus the order's
 * store scope. Retry safety comes from the state machine itself — an order
 * already in the target state is an idempotent early return (200), while a
 * genuinely illegal transition is 409 ORDER_STATE_CONFLICT; that is why no
 * Idempotency-Key is required here. Cancel releases the order's still-ACTIVE
 * ingredient reservation through the finalization service (exactly once, reason
 * ORDER_CANCELLED); a reservation that settlement already consumed is left
 * alone, so cancellation can never give consumed stock back. Refunding a PAID
 * order is out of scope (paymentStatus stays visible in the response and
 * management reads).
 */
@Service
public class OrderCommandService {

    private final OrderRepository orders;
    private final StoreAuthorizationService storeAuth;
    private final InventoryReservationFinalizationService reservations;
    private final PromotionRedemptionLifecycleService promotions;
    private final AuditWriter audit;
    private final OutboxEventWriter outbox;
    private final OrderMapper mapper;
    private final ObjectMapper json;

    public OrderCommandService(OrderRepository o, StoreAuthorizationService s,
            InventoryReservationFinalizationService r, PromotionRedemptionLifecycleService p, AuditWriter a,
            OutboxEventWriter out, OrderMapper m, ObjectMapper j) {
        orders = o;
        storeAuth = s;
        reservations = r;
        promotions = p;
        audit = a;
        outbox = out;
        mapper = m;
        json = j;
    }

    @Transactional
    public OrderResponse cancel(CurrentActor actor, UUID orderId, CancelOrderRequest request) {
        Order order = lock(orderId);
        String reason = request.reason().trim();
        if (order.status() == OrderStatus.CANCELLED) {
            return mapper.response(order, false); // idempotent replay of the command
        }
        storeAuth.requireStore(actor, order.storeId());
        var before = order.status().name();
        order.cancel(actor.userId(), reason);
        reservations.releaseOrder(order.id(), ReservationReasons.ORDER_CANCELLED, actor.userId());
        // Only a still-RESERVED promotion is given back; a REDEEMED one is final
        // in B2 (refund/reversal workflow is out of scope).
        promotions.releaseForOrder(order.id(), "ORDER_CANCELLED", actor.userId());
        emit(order, EventTypes.ORDER_CANCELLED, "order.cancelled.v1",
                Map.of("reason", reason, "fromStatus", before));
        audit.success(actor.userId(), order.storeId(), "ORDER_CANCELLED", "ORDER", "ORDER", order.id(),
                Map.of("status", before), Map.of("status", order.status().name()), reason);
        return mapper.response(order, false);
    }

    @Transactional
    public OrderResponse complete(CurrentActor actor, UUID orderId) {
        Order order = lock(orderId);
        if (order.status() == OrderStatus.COMPLETED) {
            return mapper.response(order, false); // idempotent replay of the command
        }
        storeAuth.requireStore(actor, order.storeId());
        var before = order.status().name();
        order.completed(actor.userId()); // 409 ORDER_STATE_CONFLICT unless READY
        emit(order, EventTypes.ORDER_COMPLETED, "order.completed.v1",
                Map.of("fromStatus", before));
        audit.success(actor.userId(), order.storeId(), "ORDER_COMPLETED", "ORDER", "ORDER", order.id(),
                Map.of("status", before), Map.of("status", order.status().name()), null);
        return mapper.response(order, false);
    }

    private Order lock(UUID orderId) {
        return orders.lock(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("ORDER_NOT_FOUND", "Order not found"));
    }

    private void emit(Order order, String eventType, String routingKey, Map<String, String> extras) {
        var payload = json.createObjectNode().put("orderId", order.id().toString())
                .put("orderNumber", order.number()).put("storeId", order.storeId().toString())
                .put("status", order.status().name());
        extras.forEach(payload::put);
        outbox.append(new EventEnvelope(UUID.randomUUID(), eventType, 1, "ORDER", order.id(), order.storeId(),
                Instant.now(), null, null, payload), routingKey);
    }
}
