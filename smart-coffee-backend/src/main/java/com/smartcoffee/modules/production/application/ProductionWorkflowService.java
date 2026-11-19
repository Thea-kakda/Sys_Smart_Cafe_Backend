package com.smartcoffee.modules.production.application;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.smartcoffee.common.exception.*;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.infrastructure.idempotency.*;
import com.smartcoffee.infrastructure.messaging.*;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.infrastructure.realtime.RealtimeEventPublisher;
import com.smartcoffee.modules.production.api.ProductionTicketResponse;
import com.smartcoffee.modules.production.domain.*;
import com.smartcoffee.modules.production.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionWorkflowService {
    private final ProductionTicketItemRepository items;
    private final ProductionTicketRepository tickets;
    private final ProductionStationRepository stations;
    private final ProductionOrderPort orders;
    private final ProductionMapper mapper;
    private final IdempotencyService idem;
    private final CanonicalRequestHasher hasher;
    private final OutboxEventWriter outbox;
    private final RealtimeEventPublisher realtime;
    private final ObjectMapper json;
    private final StoreAuthorizationService stores;

    public record Action(UUID ticketItemId, String action) {
    }

    public ProductionWorkflowService(ProductionTicketItemRepository i, ProductionTicketRepository t,
            ProductionStationRepository s, ProductionOrderPort o, ProductionMapper m,
            IdempotencyService id, CanonicalRequestHasher h, OutboxEventWriter out, RealtimeEventPublisher rt,
            ObjectMapper j, StoreAuthorizationService auth) {
        items = i;
        tickets = t;
        stations = s;
        orders = o;
        mapper = m;
        idem = id;
        hasher = h;
        outbox = out;
        realtime = rt;
        json = j;
        stores = auth;
    }

    @Transactional
    public ProductionTicketResponse start(CurrentActor actor, UUID key, UUID itemId) {
        var claim = idem.claim("PRODUCTION_START", "USER", actor.userId(), key, hasher.hash(new Action(itemId, "START")),
                Duration.ofDays(1));
        if (claim.status() == IdempotencyStatus.COMPLETED)
            return replay(claim);
        var item = items.lock(itemId).orElseThrow(() -> new ResourceNotFoundException("Production item not found"));
        var ticket = tickets.lock(findTicketId(itemId)).orElseThrow();
        // Store-scope / IDOR guard: holding production:item-start is not enough —
        // the actor must be assigned to the branch that owns this ticket.
        stores.requireStore(actor, ticket.storeId());
        item.start(actor.userId());
        boolean first = ticket.status() == ProductionStatus.PENDING;
        ticket.recalculate();
        if (first) {
            // Stock is drawn down at settlement, not here (Slice 2D): the order's
            // reservation was consumed by the payment that confirmed it, so
            // starting production only advances the order state.
            orders.markPreparing(ticket.orderId(), actor.userId());
        }
        var station = stations.findById(ticket.stationId()).orElseThrow();
        event(ticket, item, "ProductionItemStarted", "production.item.started.v1");
        realtime.toStore(ticket.storeId().toString(), "production/" + station.type().name().toLowerCase(),
                payload(ticket, item, "PRODUCTION_ITEM_STARTED"));
        return complete(claim, mapper.response(ticket, station));
    }

    @Transactional
    public ProductionTicketResponse ready(CurrentActor actor, UUID key, UUID itemId) {
        var claim = idem.claim("PRODUCTION_READY", "USER", actor.userId(), key, hasher.hash(new Action(itemId, "READY")),
                Duration.ofDays(1));
        if (claim.status() == IdempotencyStatus.COMPLETED)
            return replay(claim);
        var item = items.lock(itemId).orElseThrow(() -> new ResourceNotFoundException("Production item not found"));
        var ticket = tickets.lock(findTicketId(itemId)).orElseThrow();
        // Store-scope / IDOR guard (see start): a staff member can only action
        // tickets belonging to a branch they are authorized to operate.
        stores.requireStore(actor, ticket.storeId());
        item.ready(actor.userId());
        ticket.recalculate();
        var station = stations.findById(ticket.stationId()).orElseThrow();
        event(ticket, item, "ProductionItemReady", "production.item.ready.v1");
        if (tickets.findByOrderId(ticket.orderId()).stream().allMatch(t -> t.status() == ProductionStatus.READY)) {
            orders.markReady(ticket.orderId(), actor.userId());
            realtime.toStore(ticket.storeId().toString(), "production/ready", payload(ticket, item, "ORDER_READY"));
        }
        return complete(claim, mapper.response(ticket, station));
    }

    private UUID findTicketId(UUID itemId) {
        return items.ticketId(itemId).orElseThrow(() -> new ResourceNotFoundException("Production ticket not found"));
    }

    private ObjectNode payload(
            ProductionTicket ticket,
            ProductionTicketItem item,
            String type) {
        return json.createObjectNode()
                .put("type", type)
                .put("ticketId", ticket.id().toString())
                .put("ticketItemId", item.id().toString())
                .put("orderId", ticket.orderId().toString())
                .put("storeId", ticket.storeId().toString())
                .put("status", item.status().name());
    }

    private void event(ProductionTicket t, ProductionTicketItem i, String type, String route) {
        outbox.append(new EventEnvelope(UUID.randomUUID(), type, 1, "PRODUCTION_TICKET", t.id(), t.storeId(),
                Instant.now(), null, null, payload(t, i, type)), route);
    }

    private ProductionTicketResponse complete(ApiIdempotencyRecord c, ProductionTicketResponse r) {
        try {
            c.complete(200, json.writeValueAsString(r));
            return r;
        } catch (Exception e) {
            throw new InfrastructureException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot store response");
        }
    }

    private ProductionTicketResponse replay(ApiIdempotencyRecord c) {
        try {
            return json.readValue(c.responseBody(), ProductionTicketResponse.class);
        } catch (Exception e) {
            throw new InfrastructureException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot replay response");
        }
    }
}

