package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "inventory", name = "inventory_reservation_lines", uniqueConstraints = @UniqueConstraint(columnNames = {
        "reservation_id", "ingredient_id" }))
public class InventoryReservationLine {
    @Id
    @Column(name = "reservation_line_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private InventoryReservation reservation;
    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;
    @Column(name = "quantity_reserved", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityReserved;
    @Column(name = "quantity_consumed", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityConsumed;
    /** Base inventory unit the held quantity is expressed in (V045, readable snapshot). */
    @Column(name = "base_unit_code", length = 20)
    private String baseUnitCode;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InventoryReservationLine() {
    }

    public InventoryReservationLine(UUID ingredient, BigDecimal quantity) {
        this(ingredient, quantity, null);
    }

    public InventoryReservationLine(UUID ingredient, BigDecimal quantity, String baseUnitCode) {
        id = UUID.randomUUID();
        ingredientId = ingredient;
        quantityReserved = quantity;
        quantityConsumed = BigDecimal.ZERO;
        this.baseUnitCode = baseUnitCode;
        createdAt = Instant.now();
    }

    void attach(InventoryReservation r) {
        reservation = r;
    }

    /** Delta engine positive side: the line holds more of the ingredient. */
    void add(BigDecimal quantity) {
        quantityReserved = quantityReserved.add(quantity);
    }

    /** Delta engine negative side: the line holds less (never below zero). */
    void quantityReserved(BigDecimal quantity) {
        quantityReserved = quantity;
    }

    public UUID ingredientId() {
        return ingredientId;
    }

    public BigDecimal quantityReserved() {
        return quantityReserved;
    }

    public String baseUnitCode() {
        return baseUnitCode;
    }

    public void consume() {
        quantityConsumed = quantityReserved;
    }
}
