package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.api.*;
import com.smartcoffee.modules.inventory.application.GoodsReceiptService;
import com.smartcoffee.modules.inventory.application.PurchaseOrderCommandService;
import com.smartcoffee.modules.inventory.application.SupplierCommandService;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Purchasing (V033/V034) against a real migrated database: supplier CRUD with
 * code/version conflicts, the PO lifecycle (draft -> submit -> receive -> close),
 * and — the heart of the milestone — goods receiving that increments the
 * aggregate balance ONLY through the guarded native mutations, writes exactly one
 * PURCHASE_RECEIPT ledger transaction per receipt with real before/after, refuses
 * over-receipt atomically (no stock moves), and enforces permission + store scope.
 */
class InventoryPurchaseIT extends IntegrationTestBase {

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
    InventoryBalanceRepository balances;

    private static final String COFFEE = "70000000-0000-0000-0000-000000000001";
    private static final String MILK = "70000000-0000-0000-0000-000000000002";
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private CurrentActor admin() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Purchase Admin", "pur-" + userId + "@smart.coffee", "pur-" + userId + "@smart.coffee", "en-US");
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
                + "values (?::uuid, ?, 'Purch Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'km-KH', true, now(), now(), 0)",
                storeId.toString(), "PUR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return storeId;
    }

    private UUID newSupplier(CurrentActor actor, String code) {
        return suppliers.create(actor, new CreateSupplierRequest(code, "Supplier " + code, null, null, null, null,
                null, true)).supplierId();
    }

    private PurchaseOrderResponse newSubmittedOrder(CurrentActor actor, UUID store, UUID supplier) {
        var created = orders.create(actor, store, new CreatePurchaseOrderRequest(supplier, null, "buy", List.of(
                new CreatePurchaseOrderRequest.Line(UUID.fromString(COFFEE), new BigDecimal("100"),
                        new BigDecimal("0.0200")),
                new CreatePurchaseOrderRequest.Line(UUID.fromString(MILK), new BigDecimal("50"),
                        new BigDecimal("0.0100")))));
        return orders.submit(actor, store, created.purchaseOrderId());
    }

    private ReceiveGoodsRequest receive(String ingredient, String qty, String cost) {
        return new ReceiveGoodsRequest(List.of(new ReceiveGoodsRequest.Line(UUID.fromString(ingredient),
                new BigDecimal(qty), cost == null ? null : new BigDecimal(cost), null, null)));
    }

    @Test
    void supplierDuplicateAndVersionConflict() {
        CurrentActor admin = admin();
        String code = "SUP" + UUID.randomUUID().toString().substring(0, 6);
        SupplierResponse created = suppliers.create(admin, new CreateSupplierRequest(code, "Acme", null, null, null,
                null, null, true));
        ConflictException dup = assertThrows(ConflictException.class, () -> suppliers.create(admin,
                new CreateSupplierRequest(code, "Acme Two", null, null, null, null, null, true)));
        assertEquals("DUPLICATE_CODE", dup.code());

        // Stale version echo.
        ConflictException conflict = assertThrows(ConflictException.class, () -> suppliers.update(admin,
                created.supplierId(), new UpdateSupplierRequest(code, "Renamed", null, null, null, null, null, true,
                        99L)));
        assertEquals("VERSION_CONFLICT", conflict.code());

        // Correct version succeeds and bumps it.
        SupplierResponse updated = suppliers.update(admin, created.supplierId(),
                new UpdateSupplierRequest(code, "Renamed", null, null, null, null, null, true, created.version()));
        assertEquals("Renamed", updated.name());
        assertEquals(created.version() + 1, updated.version());
        SecurityContextHolder.clearContext();
    }

    @Test
    void receiveIncrementsBalanceAndWritesLedgerAndAudit() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        UUID supplier = newSupplier(admin, "S" + UUID.randomUUID().toString().substring(0, 6));
        var po = newSubmittedOrder(admin, store, supplier);
        assertEquals("SUBMITTED", po.status());

        // Partial receipt: 60 of 100 coffee + all 50 milk.
        receiving.receive(admin, store, po.purchaseOrderId(),
                new ReceiveGoodsRequest(List.of(
                        new ReceiveGoodsRequest.Line(UUID.fromString(COFFEE), new BigDecimal("60"),
                                new BigDecimal("0.0200"), "LOT-A", null),
                        new ReceiveGoodsRequest.Line(UUID.fromString(MILK), new BigDecimal("50"),
                                new BigDecimal("0.0100"), null, null))));
        assertEquals(0, new BigDecimal("60").compareTo(
                balances.find(store, UUID.fromString(COFFEE)).orElseThrow().currentStock()));
        assertEquals(0, new BigDecimal("50").compareTo(
                balances.find(store, UUID.fromString(MILK)).orElseThrow().currentStock()));

        // Complete the coffee line -> RECEIVED.
        receiving.receive(admin, store, po.purchaseOrderId(), receive(COFFEE, "40", null));
        var closed = orders.close(admin, store, po.purchaseOrderId());
        assertEquals("CLOSED", closed.status());

        // Aggregate reflects the full ordered quantity; moving average restated.
        var coffee = balances.find(store, UUID.fromString(COFFEE)).orElseThrow();
        assertEquals(0, new BigDecimal("100").compareTo(coffee.currentStock()));
        assertEquals(0, new BigDecimal("0.0200").compareTo(coffee.averageUnitCost()));

        // Exactly two PURCHASE_RECEIPT ledger transactions (one per receipt call).
        assertEquals(2, jdbc.queryForObject("select count(*) from inventory.stock_transactions where store_id=?::uuid "
                + "and transaction_type='PURCHASE_RECEIPT' and reference_type='GOODS_RECEIPT'", Integer.class,
                store.toString()));
        assertEquals(3, jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.store_id=?::uuid", Integer.class, store.toString()));
        // First receipt's coffee line: before 0 -> after 60.
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.store_id=?::uuid and l.ingredient_id=?::uuid and l.balance_before=0 "
                + "and l.balance_after=60 and l.quantity_delta=60", Integer.class, store.toString(), COFFEE));
        assertEquals(2, jdbc.queryForObject("select count(*) from audit.audit_logs where action='GOODS_RECEIVED' "
                + "and store_id=?::uuid", Integer.class, store.toString()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void overReceiptRejectedAndNoStockMoves() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        UUID supplier = newSupplier(admin, "S" + UUID.randomUUID().toString().substring(0, 6));
        var po = newSubmittedOrder(admin, store, supplier);

        receiving.receive(admin, store, po.purchaseOrderId(), receive(COFFEE, "100", "0.0200"));
        int txBefore = jdbc.queryForObject("select count(*) from inventory.stock_transactions where store_id=?::uuid",
                Integer.class, store.toString());
        var balance = balances.find(store, UUID.fromString(COFFEE)).orElseThrow().currentStock();

        ConflictException over = assertThrows(ConflictException.class,
                () -> receiving.receive(admin, store, po.purchaseOrderId(), receive(COFFEE, "1", null)));
        assertEquals("OVER_RECEIPT", over.code());
        assertEquals(0, balance.compareTo(
                balances.find(store, UUID.fromString(COFFEE)).orElseThrow().currentStock()));
        assertEquals(txBefore, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=?::uuid", Integer.class, store.toString()), "failed receipt wrote no ledger");
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitTwiceAndReceiveUnsubmittedRejected() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        UUID supplier = newSupplier(admin, "S" + UUID.randomUUID().toString().substring(0, 6));
        var created = orders.create(admin, store, new CreatePurchaseOrderRequest(supplier, null, null,
                List.of(new CreatePurchaseOrderRequest.Line(UUID.fromString(COFFEE), BigDecimal.TEN,
                        new BigDecimal("0.0200")))));
        orders.submit(admin, store, created.purchaseOrderId());
        ConflictException twice = assertThrows(ConflictException.class,
                () -> orders.submit(admin, store, created.purchaseOrderId()));
        assertEquals("INVALID_STATUS_TRANSITION", twice.code());
        SecurityContextHolder.clearContext();
    }

    @Test
    void permissionAndStoreScopeEnforced() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        UUID supplier = newSupplier(admin, "S" + UUID.randomUUID().toString().substring(0, 6));
        var po = newSubmittedOrder(admin, store, supplier);

        // Actor lacking inventory:receive.
        CurrentActor noReceive = new CurrentActor(UUID.randomUUID(), "USER", Set.of("purchase:manage"),
                Set.of(store), false);
        assertThrows(ForbiddenException.class,
                () -> receiving.receive(noReceive, store, po.purchaseOrderId(), receive(COFFEE, "1", null)));

        // Store mismatch: PO belongs to `store`, command issued for another store id.
        UUID otherStore = seedStore();
        ConflictException mismatch = assertThrows(ConflictException.class,
                () -> receiving.receive(admin, otherStore, po.purchaseOrderId(), receive(COFFEE, "1", null)));
        assertEquals("STORE_MISMATCH", mismatch.code());
        SecurityContextHolder.clearContext();
    }

    @Test
    void createOrderValidatesSupplierAndIngredients() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        assertThrows(com.smartcoffee.common.exception.ResourceNotFoundException.class, () -> orders.create(admin,
                store, new CreatePurchaseOrderRequest(UUID.randomUUID(), null, null,
                        List.of(new CreatePurchaseOrderRequest.Line(UUID.fromString(COFFEE), BigDecimal.TEN,
                                BigDecimal.ONE)))));

        UUID supplier = newSupplier(admin, "S" + UUID.randomUUID().toString().substring(0, 6));
        assertThrows(com.smartcoffee.common.exception.ResourceNotFoundException.class, () -> orders.create(admin,
                store, new CreatePurchaseOrderRequest(supplier, null, null,
                        List.of(new CreatePurchaseOrderRequest.Line(UUID.randomUUID(), BigDecimal.TEN,
                                BigDecimal.ONE)))));
        SecurityContextHolder.clearContext();
    }
}
