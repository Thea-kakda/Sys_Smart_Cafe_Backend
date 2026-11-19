package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.api.*;
import com.smartcoffee.modules.inventory.application.StockTransferService;
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
 * Inter-branch transfers (V035/V037) against a real migrated database. The heart
 * of M2: dispatch draws down the SOURCE and receive brings stock into the DEST,
 * each through the guarded native mutations (never a setter), stamping exactly
 * one TRANSFER_OUT / TRANSFER_IN ledger transaction with real before/after and
 * restating the destination moving average. Verifies the no-silent-negative
 * invariant (insufficient source stock rolls back with no ledger written), the
 * status machine, and permission + both-store scope enforcement.
 */
class StockTransferIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    StockTransferService transfers;
    @Autowired
    InventoryBalanceRepository balances;

    private static final String COFFEE = "70000000-0000-0000-0000-000000000001";
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private CurrentActor admin() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Transfer Admin", "trf-" + userId + "@smart.coffee", "trf-" + userId + "@smart.coffee", "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?::uuid, ?::uuid, ?::uuid, null, now())", UUID.randomUUID(), userId,
                ADMIN_ROLE_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    private UUID seedStore(String tag) {
        UUID storeId = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?::uuid, ?, 'Transfer " + tag + "', 'Addr', 'Asia/Phnom_Penh', 'USD', 'km-KH', true, "
                + "now(), now(), 0)", storeId.toString(), "TRF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return storeId;
    }

    private void seedStock(UUID store, String ingredient, String qty) {
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id) values (?::uuid, ?::uuid) "
                + "on conflict (store_id, ingredient_id) do nothing", store.toString(), ingredient);
        jdbc.update("update inventory.inventory_balances set current_stock=?::numeric, average_unit_cost=0 "
                + "where store_id=?::uuid and ingredient_id=?::uuid", qty, store.toString(), ingredient);
    }

    private CreateStockTransferRequest request(UUID source, UUID dest, String ingredient, String qty, String cost) {
        return new CreateStockTransferRequest(source, dest, "move", List.of(
                new CreateStockTransferRequest.Line(UUID.fromString(ingredient), new BigDecimal(qty),
                        new BigDecimal(cost))));
    }

    @Test
    void dispatchThenReceiveMovesStockAndWritesPairedLedger() {
        CurrentActor admin = admin();
        UUID source = seedStore("src");
        UUID dest = seedStore("dst");
        seedStock(source, COFFEE, "100");

        var created = transfers.request(admin, request(source, dest, COFFEE, "30", "0.0500"));
        assertEquals("REQUESTED", created.status());

        var dispatched = transfers.dispatch(admin, created.stockTransferId());
        assertEquals("IN_TRANSIT", dispatched.status());
        assertEquals(0, new BigDecimal("70").compareTo(
                balances.find(source, UUID.fromString(COFFEE)).orElseThrow().currentStock()), "source drew down");
        assertNull(balances.find(dest, UUID.fromString(COFFEE)).orElse(null), "dest untouched until receive");

        var received = transfers.receive(admin, created.stockTransferId());
        assertEquals("RECEIVED", received.status());
        var destBal = balances.find(dest, UUID.fromString(COFFEE)).orElseThrow();
        assertEquals(0, new BigDecimal("30").compareTo(destBal.currentStock()));
        assertEquals(0, new BigDecimal("0.0500").compareTo(destBal.averageUnitCost()), "dest moving average restated");

        // One TRANSFER_OUT at the source + one TRANSFER_IN at the dest, referencing the transfer.
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory.stock_transactions where "
                + "transaction_type='TRANSFER_OUT' and store_id=?::uuid and reference_type='STOCK_TRANSFER'",
                Integer.class, source.toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory.stock_transactions where "
                + "transaction_type='TRANSFER_IN' and store_id=?::uuid and reference_type='STOCK_TRANSFER'",
                Integer.class, dest.toString()));
        // OUT line carries a negative delta with real before/after (100 -> 70).
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.store_id=?::uuid and l.balance_before=100 and l.balance_after=70 and l.quantity_delta=-30",
                Integer.class, source.toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit.audit_logs where action='STOCK_TRANSFER_DISPATCHED'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit.audit_logs where action='STOCK_TRANSFER_RECEIVED'",
                Integer.class));
        SecurityContextHolder.clearContext();
    }

    @Test
    void dispatchInsufficientStockRollsWithoutLedger() {
        CurrentActor admin = admin();
        UUID source = seedStore("src");
        UUID dest = seedStore("dst");
        seedStock(source, COFFEE, "10");

        var created = transfers.request(admin, request(source, dest, COFFEE, "50", "0.0500"));
        ConflictException short_ = assertThrows(ConflictException.class,
                () -> transfers.dispatch(admin, created.stockTransferId()));
        assertEquals("INSUFFICIENT_STOCK", short_.code());
        assertEquals(0, new BigDecimal("10").compareTo(
                balances.find(source, UUID.fromString(COFFEE)).orElseThrow().currentStock()), "source unchanged");
        assertEquals(0, jdbc.queryForObject("select count(*) from inventory.stock_transactions where "
                + "transaction_type='TRANSFER_OUT' and store_id=?::uuid", Integer.class, source.toString()),
                "failed dispatch wrote no ledger");
        SecurityContextHolder.clearContext();
    }

    @Test
    void receiveRequiresDispatchFirstAndDoubleDispatchRejected() {
        CurrentActor admin = admin();
        UUID source = seedStore("src");
        UUID dest = seedStore("dst");
        seedStock(source, COFFEE, "100");

        var created = transfers.request(admin, request(source, dest, COFFEE, "20", "0.0500"));
        ConflictException early = assertThrows(ConflictException.class,
                () -> transfers.receive(admin, created.stockTransferId()));
        assertEquals("INVALID_STATUS_TRANSITION", early.code());

        transfers.dispatch(admin, created.stockTransferId());
        ConflictException twice = assertThrows(ConflictException.class,
                () -> transfers.dispatch(admin, created.stockTransferId()));
        assertEquals("INVALID_STATUS_TRANSITION", twice.code());
        SecurityContextHolder.clearContext();
    }

    @Test
    void cancelOnlyBeforeDispatch() {
        CurrentActor admin = admin();
        UUID source = seedStore("src");
        UUID dest = seedStore("dst");
        seedStock(source, COFFEE, "100");

        var created = transfers.request(admin, request(source, dest, COFFEE, "20", "0.0500"));
        assertEquals("CANCELLED", transfers.cancel(admin, created.stockTransferId(), "oops").status());

        var dispatched = transfers.request(admin, request(source, dest, COFFEE, "20", "0.0500"));
        transfers.dispatch(admin, dispatched.stockTransferId());
        ConflictException strand = assertThrows(ConflictException.class,
                () -> transfers.cancel(admin, dispatched.stockTransferId(), "too late"));
        assertEquals("TRANSFER_IN_TRANSIT", strand.code());
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameStoreAndCrossScopeRejected() {
        CurrentActor admin = admin();
        UUID store = seedStore("one");
        ValidationException same = assertThrows(ValidationException.class,
                () -> transfers.request(admin, request(store, store, COFFEE, "5", "0.0500")));
        assertEquals("SAME_STORE", same.code());

        // Actor scoped to a single store cannot target a destination outside scope.
        UUID src = seedStore("src");
        UUID outside = seedStore("outside");
        CurrentActor scoped = new CurrentActor(UUID.randomUUID(), "USER", Set.of("inventory:transfer"),
                Set.of(src), false);
        assertThrows(ForbiddenException.class,
                () -> transfers.request(scoped, request(src, outside, COFFEE, "5", "0.0500")));
        SecurityContextHolder.clearContext();
    }
}
