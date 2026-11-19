package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "inventory", name = "stock_transaction_lines")
public class StockTransactionLine {
    @Id
    @Column(name = "stock_transaction_line_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_transaction_id", nullable = false)
    private StockTransaction transaction;
    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;
    @Column(name = "quantity_delta", nullable = false, precision = 18, scale = 4)
    private BigDecimal delta;
    @Column(name = "unit_cost", precision = 14, scale = 4)
    private BigDecimal unitCost;
    @Column(name = "balance_before", nullable = false, precision = 18, scale = 4)
    private BigDecimal before;
    @Column(name = "balance_after", nullable = false, precision = 18, scale = 4)
    private BigDecimal after;
    // Optional lot/batch this line drew from (V038). Null for untracked
    // ingredients and valued stock-ins, so every existing ledger row is
    // unaffected; a FEFO consumption stamps the batch on its own line.
    @Column(name = "batch_id")
    private UUID batchId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockTransactionLine() {
    }

    public StockTransactionLine(UUID ingredient, BigDecimal delta, BigDecimal cost, BigDecimal before,
            BigDecimal after) {
        this(ingredient, delta, cost, before, after, null);
    }

    public StockTransactionLine(UUID ingredient, BigDecimal delta, BigDecimal cost, BigDecimal before,
            BigDecimal after, UUID batchId) {
        id = UUID.randomUUID();
        ingredientId = ingredient;
        this.delta = delta;
        unitCost = cost;
        this.before = before;
        this.after = after;
        this.batchId = batchId;
        createdAt = Instant.now();
    }

    void attach(StockTransaction t) {
        transaction = t;
    }
}

