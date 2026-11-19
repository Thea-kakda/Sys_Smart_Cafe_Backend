package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.InventoryReservationLine;

import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order-level reservation primitives that are <b>not</b> part of a lifecycle
 * transition: creating a hold and the raw id-addressed release/expiry used by
 * maintenance paths.
 *
 * <p>Settlement consumption and order cancellation release live in
 * {@link InventoryReservationFinalizationService}, which owns the exactly-once
 * semantics (lock order, reason vocabulary, ledger provenance, idempotency).
 */
@Service
public class InventoryReservationService {
    private final InventoryBalanceRepository balances;
    private final InventoryReservationRepository reservations;

    public InventoryReservationService(InventoryBalanceRepository b, InventoryReservationRepository r) {
        balances = b;
        reservations = r;
    }

    @Transactional
    public UUID reserve(UUID store, UUID order, Map<UUID, BigDecimal> demand, Duration ttl) {
        if (reservations.findByOrderId(order).isPresent())
            return reservations.findByOrderId(order).orElseThrow().id();
        List<InventoryReservationLine> lines = new ArrayList<>();
        demand.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (balances.reserve(store, e.getKey(), e.getValue()) != 1)
                throw new ConflictException("INSUFFICIENT_STOCK", "Insufficient ingredient stock");
            lines.add(new InventoryReservationLine(e.getKey(), e.getValue()));
        });
        return reservations.save(InventoryReservation.create(store, order, Instant.now().plus(ttl), lines)).id();
    }

    @Transactional
    public void release(UUID reservationId) {
        var r = reservations.lock(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found"));
        r.lines().stream().sorted(Comparator.comparing((InventoryReservationLine line) -> line.ingredientId())).forEach(l -> {
            if (balances.release(r.storeId(), l.ingredientId(), l.quantityReserved()) != 1)
                throw new ConflictException("INVENTORY_BALANCE_CONFLICT", "Unable to release stock");
        });
        r.released();
    }

    @Transactional
    public void expire(UUID id) {
        var r = reservations.lock(id).orElseThrow(() -> new ResourceNotFoundException("Reservation not found"));
        if (r.expiresAt().isAfter(Instant.now()))
            return;
        r.lines().stream().sorted(Comparator.comparing((InventoryReservationLine line) -> line.ingredientId())).forEach(l -> {
            if (balances.release(r.storeId(), l.ingredientId(), l.quantityReserved()) != 1)
                throw new ConflictException("INVENTORY_BALANCE_CONFLICT", "Unable to expire reservation");
        });
        r.expired();
    }
}

