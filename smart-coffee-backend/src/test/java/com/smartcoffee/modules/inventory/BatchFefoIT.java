package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.api.*;
import com.smartcoffee.modules.inventory.application.BatchService;
import com.smartcoffee.modules.inventory.application.GoodsReceiptService;
import com.smartcoffee.modules.inventory.application.InventoryAdjustmentService;
import com.smartcoffee.modules.inventory.application.PurchaseOrderCommandService;
import com.smartcoffee.modules.inventory.application.SupplierCommandService;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Batch / lot + expiry + FEFO (V038, M3) against a real migrated database. Uses
 * freshly-inserted dedicated ingredients (never the shared seeded COFFEE/MILK),
 * so turning batch tracking on here cannot disturb the other inventory ITs.
 *
 * <p>Verifies the whole M3 boundary: a receipt for a {@code track_batches}
 * ingredient opens lots whose active sum equals the aggregate balance; a POS
 * consumption draws the earliest-expiry lot first and stamps the batch id on its
 * own ledger line; a write-off retires a lot and drives the matching quantity
 * out of the aggregate through the guarded statement with a DAMAGED ledger line;
 * and an untracked ingredient stays entirely batch-free (regression guard for
 * A2 — FEFO is opt-in and invisible to untracked flows).
 */
class BatchFefoIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    SupplierCommandService suppliers;
    @Autowired
    PurchaseOrderCommandService orders;
    @Autowired
    GoodsReceiptService receiving;
    @Autowired
    InventoryAdjustmentService adjustments;
    @Autowired
    BatchService batchService;
    @Autowired
    InventoryBalanceRepository balances;

    private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private CurrentActor admin() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Batch Admin", "bat-" + userId + "@smart.coffee", "bat-" + userId + "@smart.coffee", "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?::uuid, ?::uuid, ?::uuid, null, now())", UUID.randomUUID(), userId,
                ADMIN_ROLE_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    private UUID seedStore() {
        UUID storeId = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?::uuid, ?, 'Batch Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'km-KH', true, now(), now(), 0)",
                storeId.toString(), "BAT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return storeId;
    }

    /** Inserts a dedicated ingredient (tracked or not) reusing a real base unit id. */
    private UUID seedIngredient(boolean trackBatches) {
        UUID unitId = jdbc.queryForObject("select unit_id from inventory.units_of_measure order by unit_id limit 1",
                UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, base_unit_id, "
                + "standard_cost, track_inventory, active, track_batches, shelf_life_days, created_at, updated_at, "
                + "version) values (?::uuid, ?, ?, ?::uuid, 0, true, true, ?, null, now(), now(), 0)",
                id.toString(), "BING" + id.toString().substring(0, 8).toUpperCase(), "Batch Ingredient " + id,
                unitId.toString(), trackBatches);
        return id;
    }

    private UUID newSupplier(CurrentActor actor) {
        String code = "S" + UUID.randomUUID().toString().substring(0, 8);
        return suppliers.create(actor, new CreateSupplierRequest(code, "Supplier " + code, null, null, null, null,
                null, true)).supplierId();
    }

    private PurchaseOrderResponse submittedOrder(CurrentActor actor, UUID store, UUID supplier, UUID ingredient,
            String qty) {
        var created = orders.create(actor, store, new CreatePurchaseOrderRequest(supplier, null, "buy",
                List.of(new CreatePurchaseOrderRequest.Line(ingredient, new BigDecimal(qty),
                        new BigDecimal("0.0200")))));
        return orders.submit(actor, store, created.purchaseOrderId());
    }

    private void receive(CurrentActor actor, UUID store, UUID poId, UUID ingredient, String qty, String lot,
            LocalDate expiry) {
        receiving.receive(actor, store, poId, new ReceiveGoodsRequest(List.of(
                new ReceiveGoodsRequest.Line(ingredient, new BigDecimal(qty), new BigDecimal("0.0200"), lot,
                        expiry))));
    }

    private BigDecimal balance(UUID store, UUID ingredient) {
        return balances.find(store, ingredient).map(b -> b.currentStock()).orElse(BigDecimal.ZERO);
    }

    @Test
    void receiptOpensBatchesSummingToAggregate() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        UUID coffee = seedIngredient(true);

        var po = submittedOrder(admin, store, newSupplier(admin), coffee, "100");
        receive(admin, store, po.purchaseOrderId(), coffee, "100", "LOT-A", LocalDate.now().plusDays(90));

        assertEquals(0, new BigDecimal("100").compareTo(balance(store, coffee)));
        BigDecimal batchSum = jdbc.queryForObject(
                "select coalesce(sum(quantity_base),0) from inventory.inventory_batches where store_id=?::uuid "
                        + "and ingredient_id=?::uuid and status='ACTIVE'", BigDecimal.class, store.toString(),
                coffee.toString());
        assertEquals(0, new BigDecimal("100").compareTo(batchSum), "active lot sum equals the aggregate");
        Integer lots = jdbc.queryForObject("select count(*) from inventory.inventory_batches where store_id=?::uuid "
                + "and lot_number='LOT-A'", Integer.class, store.toString());
        assertEquals(1, lots);
        SecurityContextHolder.clearContext();
    }

    @Test
    void consumptionDrawsEarliestExpiryFirstAndStampsBatch() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        UUID coffee = seedIngredient(true);
        var po = submittedOrder(admin, store, newSupplier(admin), coffee, "100");

        receive(admin, store, po.purchaseOrderId(), coffee, "40", "LOT-NEAR", LocalDate.now().plusDays(7));
        receive(admin, store, po.purchaseOrderId(), coffee, "60", "LOT-FAR", LocalDate.now().plusDays(30));
        assertEquals(0, new BigDecimal("100").compareTo(balance(store, coffee)));

        // Consume 50 base units. The guarded OUT draw mirrors the POS consumption
        // path (both route through FefoConsumptionService.drawLines), so it is the
        // same production code without needing a live order row.
        adjustments.adjust(admin, store, new AdjustInventoryRequest(coffee, "OUT", new BigDecimal("50"),
                "use-by-fefo", null, "ADJUSTMENT"));

        assertEquals(0, new BigDecimal("50").compareTo(balance(store, coffee)), "aggregate drew down 50");
        assertEquals("EXHAUSTED", jdbc.queryForObject("select status from inventory.inventory_batches "
                + "where store_id=?::uuid and lot_number='LOT-NEAR'", String.class, store.toString()));
        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.queryForObject("select quantity_base from inventory.inventory_batches "
                + "where store_id=?::uuid and lot_number='LOT-NEAR'", BigDecimal.class, store.toString())));
        assertEquals("ACTIVE", jdbc.queryForObject("select status from inventory.inventory_batches "
                + "where store_id=?::uuid and lot_number='LOT-FAR'", String.class, store.toString()));
        assertEquals(0, new BigDecimal("50").compareTo(jdbc.queryForObject("select quantity_base from "
                + "inventory.inventory_batches where store_id=?::uuid and lot_number='LOT-FAR'", BigDecimal.class,
                store.toString())), "later lot gave the remaining 10");

        // Two ledger lines, each stamped with the lot it drew from (-40 then -10).
        assertEquals(2, jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.store_id=?::uuid and l.quantity_delta<>0 and l.batch_id is not null",
                Integer.class, store.toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.store_id=?::uuid and l.quantity_delta=-40 and l.batch_id is not null", Integer.class,
                store.toString()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void writeOffRetiresLotAndDrivesAggregateDownWithDamaged() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        UUID coffee = seedIngredient(true);
        var po = submittedOrder(admin, store, newSupplier(admin), coffee, "100");
        receive(admin, store, po.purchaseOrderId(), coffee, "100", "LOT-W", LocalDate.now().plusDays(60));

        UUID batch = jdbc.queryForObject("select batch_id from inventory.inventory_batches where store_id=?::uuid "
                + "and lot_number='LOT-W'", UUID.class, store.toString());
        batchService.writeOff(admin.userId(), store, batch, "spoiled case");

        assertEquals(0, BigDecimal.ZERO.compareTo(balance(store, coffee)), "aggregate fell to zero");
        assertEquals("ADJUSTED_OUT", jdbc.queryForObject("select status from inventory.inventory_batches "
                + "where batch_id=?::uuid", String.class, batch.toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory.stock_transactions where store_id=?::uuid "
                + "and transaction_type='DAMAGED' and reference_type='INVENTORY_BATCH'", Integer.class,
                store.toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines where "
                + "batch_id=?::uuid and quantity_delta=-100", Integer.class, batch.toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit.audit_logs where action='BATCH_WRITTEN_OFF' "
                + "and store_id=?::uuid", Integer.class, store.toString()));

        // A second write-off of a retired lot is refused.
        assertThrows(com.smartcoffee.common.exception.ConflictException.class,
                () -> batchService.writeOff(admin.userId(), store, batch, "again"));
        SecurityContextHolder.clearContext();
    }

    @Test
    void writeOffOfPastExpiryMarksExpired() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        UUID coffee = seedIngredient(true);
        var po = submittedOrder(admin, store, newSupplier(admin), coffee, "20");
        receive(admin, store, po.purchaseOrderId(), coffee, "20", "LOT-OLD", LocalDate.now().minusDays(1));

        UUID batch = jdbc.queryForObject("select batch_id from inventory.inventory_batches where store_id=?::uuid "
                + "and lot_number='LOT-OLD'", UUID.class, store.toString());
        batchService.writeOff(admin.userId(), store, batch, "past date");
        assertEquals("EXPIRED", jdbc.queryForObject("select status from inventory.inventory_batches "
                + "where batch_id=?::uuid", String.class, batch.toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit.audit_logs where action='BATCH_EXPIRED'",
                Integer.class));
        SecurityContextHolder.clearContext();
    }

    @Test
    void untrackedIngredientStaysBatchFree() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        UUID milk = seedIngredient(false);

        adjustments.adjust(admin, store, new AdjustInventoryRequest(milk, "IN", new BigDecimal("50"),
                "opening", new BigDecimal("0.0100"), "STOCK_IN"));
        adjustments.adjust(admin, store, new AdjustInventoryRequest(milk, "OUT", new BigDecimal("10"),
                "spill", null, "DAMAGED"));

        assertEquals(0, new BigDecimal("40").compareTo(balance(store, milk)));
        assertEquals(0, jdbc.queryForObject("select count(*) from inventory.inventory_batches where store_id=?::uuid "
                + "and ingredient_id=?::uuid", Integer.class, store.toString(), milk.toString()),
                "untracked ingredient never opens a lot (A2)");
        assertEquals(0, jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.store_id=?::uuid and l.ingredient_id=?::uuid and l.batch_id is not null", Integer.class,
                store.toString(), milk.toString()), "untracked ledger lines carry no batch id");
        SecurityContextHolder.clearContext();
    }
}
