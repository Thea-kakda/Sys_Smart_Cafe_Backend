package com.smartcoffee.modules.production.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.InfrastructureException;
import com.smartcoffee.infrastructure.messaging.EventEnvelope;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.infrastructure.realtime.RealtimeEventPublisher;
import com.smartcoffee.modules.production.domain.ProductionStation;
import com.smartcoffee.modules.production.domain.ProductionTicket;
import com.smartcoffee.modules.production.domain.ProductionTicketItem;
import com.smartcoffee.modules.production.persistence.ProductionStationRepository;
import com.smartcoffee.modules.production.persistence.ProductionTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductionTicketService {

    private static final Logger log = LoggerFactory.getLogger(ProductionTicketService.class);

    private final ProductionOrderPort orders;
    private final ProductionStationRepository stations;
    private final ProductionTicketRepository tickets;
    private final OutboxEventWriter outbox;
    private final RealtimeEventPublisher realtime;
    private final ObjectMapper json;

    public ProductionTicketService(
            ProductionOrderPort orders,
            ProductionStationRepository stations,
            ProductionTicketRepository tickets,
            OutboxEventWriter outbox,
            RealtimeEventPublisher realtime,
            ObjectMapper json
    ) {
        this.orders = orders;
        this.stations = stations;
        this.tickets = tickets;
        this.outbox = outbox;
        this.realtime = realtime;
        this.json = json;
    }

    @Transactional
    public List<UUID> createForOrder(UUID orderId) {
        var order = orders.load(orderId);

        if (!"CONFIRMED".equals(order.status())) {
            throw new ConflictException(
                    "ORDER_STATE_CONFLICT",
                    "Order must be confirmed"
            );
        }

        Map<UUID, List<ProductionTicketItem>> grouped =
                new LinkedHashMap<>();

        Map<UUID, ProductionStation> resolved =
                new HashMap<>();

        for (var item : order.items()) {
            // LEGACY FALLBACK (explicitly retained): order items produced before
            // CP3B could carry a null/"UNASSIGNED" station because the old
            // checkout adapter hard-coded it. Such items are routed to BARISTA
            // and the configuration gap is logged. New branch-product
            // configurations can never omit a station — management writes are
            // guarded by StoreProductManagementService.requireActiveStoreStation,
            // so this path only ever sees historical/unmanaged data.
            String stationCode =
                    item.stationCode() == null
                            || "UNASSIGNED".equals(item.stationCode())
                            ? "BARISTA"
                            : item.stationCode();
            if (item.stationCode() == null || "UNASSIGNED".equals(item.stationCode())) {
                log.warn("Production station not configured for order item {} (product {}); "
                        + "falling back to BARISTA - fix the branch-product station",
                        item.orderItemId(), item.productName());
            }

            var station = stations
                    .findByStoreIdAndCodeAndActiveTrue(
                            order.storeId(),
                            stationCode
                    )
                    .orElseThrow(() ->
                            new InfrastructureException(
                                    "PRODUCTION_STATION_MISSING",
                                    "Production station not configured: "
                                            + stationCode
                            )
                    );

            resolved.put(station.id(), station);

            grouped.computeIfAbsent(
                    station.id(),
                    ignored -> new ArrayList<>()
            ).add(
                    new ProductionTicketItem(
                            item.orderItemId(),
                            item.productName(),
                            item.quantity(),
                            String.join(", ", item.customizations())
                    )
            );
        }

        List<UUID> ticketIds = new ArrayList<>();

        grouped.forEach((stationId, ticketItems) -> {
            ProductionStation station = resolved.get(stationId);

            String ticketNumber =
                    station.code() + "-" + order.orderNumber();

            ProductionTicket ticket = tickets.save(
                    ProductionTicket.create(
                            order.orderId(),
                            order.storeId(),
                            stationId,
                            ticketNumber,
                            ticketItems
                    )
            );

            ticketIds.add(ticket.id());

            var payload = json.createObjectNode()
                    .put("ticketId", ticket.id().toString())
                    .put("orderId", order.orderId().toString())
                    .put("storeId", order.storeId().toString())
                    .put("station", station.type().name());

            outbox.append(
                    new EventEnvelope(
                            UUID.randomUUID(),
                            "ProductionTicketCreated",
                            1,
                            "PRODUCTION_TICKET",
                            ticket.id(),
                            order.storeId(),
                            Instant.now(),
                            null,
                            null,
                            payload
                    ),
                    "production.ticket.created.v1"
            );
        });

        return ticketIds;
    }

    /**
     * Cancels every production ticket belonging to an order that was cancelled
     * downstream. This is the real consumer for {@code order.cancelled.v1}: it
     * closes the queue-vs-cancel gap where a cancelled order left stale
     * PENDING/PREPARING work permanently visible on the KDS/BDS boards.
     *
     * <p>Race-safe against in-flight start/ready commands because each ticket is
     * re-locked pessimistically before mutation, and idempotent because an
     * already-cancelled ticket is skipped — a broker redelivery therefore does
     * no extra work. Returns the number of tickets newly cancelled.</p>
     */
    @Transactional
    public int cancelForOrder(UUID orderId) {
        int cancelled = 0;

        for (ProductionTicket candidate : tickets.findByOrderId(orderId)) {
            var ticket = tickets.lock(candidate.id())
                    .orElseThrow(() -> new InfrastructureException(
                            "PRODUCTION_TICKET_MISSING",
                            "Production ticket vanished during cancellation"));

            if (!ticket.cancel()) {
                continue; // already cancelled -> idempotent no-op
            }

            var payload = json.createObjectNode()
                    .put("ticketId", ticket.id().toString())
                    .put("orderId", ticket.orderId().toString())
                    .put("storeId", ticket.storeId().toString())
                    .put("status", ticket.status().name())
                    .put("reason", "ORDER_CANCELLED");

            outbox.append(
                    new EventEnvelope(
                            UUID.randomUUID(),
                            "ProductionTicketCancelled",
                            1,
                            "PRODUCTION_TICKET",
                            ticket.id(),
                            ticket.storeId(),
                            Instant.now(),
                            null,
                            null,
                            payload
                    ),
                    "production.ticket.cancelled.v1"
            );

            stations.findById(ticket.stationId()).ifPresent(station -> realtime.toStore(
                    ticket.storeId().toString(),
                    "production/" + station.type().name().toLowerCase(),
                    payload));

            cancelled++;
        }

        return cancelled;
    }
}
