package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.api.AdjustInventoryRequest;
import com.smartcoffee.modules.inventory.api.InventoryAdjustmentResponse;
import com.smartcoffee.modules.inventory.api.StockMovementResponse;
import com.smartcoffee.modules.inventory.application.InventoryAdjustmentService;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Stock adjustments + movement ledger (Full-stack wiring §1.5) against a real
 * migrated database: guarded native balance mutations (never below zero,
 * never into reserved territory), V030 reason persisted on the ledger header,
 * one ledger transaction + line per command, audit trail, missing-row opening
 * for IN (and refusal for OUT), and the paginated/filtered movements read.
 */
class InventoryAdjustmentIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    InventoryAdjustmentService service;
    @Autowired
    InventoryBalanceRepository balances;

    private static final String COFFEE_BEAN = "70000000-0000-0000-0000-000000000001";

    private static final UUID ADMIN_ROLE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private CurrentActor authenticateAdmin() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Inv Admin", "inv-" + userId + "@smart.coffee", "inv-" + userId + "@smart.coffee", "en-US");
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
                + "values (?::uuid, ?, 'Inv Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'km-KH', true, now(), now(), 0)",
                storeId.toString(), "INV" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return storeId;
    }

    private void seedBalance(UUID store, String current, String reserved) {
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id, current_stock, "
                + "reserved_stock, minimum_stock, average_unit_cost) values (?::uuid, ?::uuid, ?, ?, 0, 0.025)",
                store.toString(), COFFEE_BEAN, new BigDecimal(current), new BigDecimal(reserved));
    }

    private AdjustInventoryRequest req(String direction, String qty, String reason, String type) {
        return new AdjustInventoryRequest(UUID.fromString(COFFEE_BEAN), direction, new BigDecimal(qty), reason, null,
                type);
    }

    @Test
    void increaseWritesLedgerReasonAndAudit() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore();
        seedBalance(store, "100", "0");

        InventoryAdjustmentResponse r = service.adjust(admin, store, req("IN", "25", "Morning delivery", "STOCK_IN"));
        assertEquals("STOCK_IN", r.type());
        assertEquals(0, new BigDecimal("125").compareTo(r.balanceAfter()));
        assertEquals("Morning delivery", r.reason());

        // Ledger header carries the V030 reason; exactly one signed line.
        assertEquals("Morning delivery", jdbc.queryForObject("select reason from inventory.stock_transactions "
                + "where stock_transaction_id=?::uuid", String.class, r.transactionId().toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines "
                + "where stock_transaction_id=?::uuid and quantity_delta=25", Integer.class,
                r.transactionId().toString()));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit.audit_logs where action='INVENTORY_ADJUSTED' "
                + "and store_id=?::uuid", Integer.class, store.toString()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void decreaseRespectsReservedAndZeroFloors() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore();
        seedBalance(store, "100", "40");

        // Available = 60: OUT 60 ok, any more breaches the reserved floor.
        assertDoesNotThrow(() -> service.adjust(admin, store, req("OUT", "60", "Spill", "DAMAGED")));
        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.adjust(admin, store, req("OUT", "1", "Beyond reserved", null)));
        assertEquals("INSUFFICIENT_STOCK", conflict.code());
        // The failed command wrote nothing: still exactly one ledger row.
        assertEquals(1, jdbc.queryForObject("select count(*) from inventory.stock_transactions where store_id=?::uuid",
                Integer.class, store.toString()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingRowOpenedForInButNotForOut() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore();

        InventoryAdjustmentResponse opened = service.adjust(admin, store, req("IN", "10", "First intake", "STOCK_IN"));
        assertEquals(0, BigDecimal.ZERO.compareTo(opened.balanceBefore()), "before must reflect the opened zero row");
        assertEquals(0, new BigDecimal("10").compareTo(
                balances.find(store, UUID.fromString(COFFEE_BEAN)).orElseThrow().currentStock()));

        UUID bare = seedStore();
        assertThrows(ConflictException.class, () -> service.adjust(admin, bare, req("OUT", "1", "Nothing there", null)));
        assertTrue(balances.find(bare, UUID.fromString(COFFEE_BEAN)).isEmpty(),
                "OUT against an untracked ingredient must not silently open a row");
        SecurityContextHolder.clearContext();
    }

    @Test
    void unitCostRestatesAverageOnlyOnIn() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore();
        seedBalance(store, "100", "0");
        service.adjust(admin, store, new AdjustInventoryRequest(UUID.fromString(COFFEE_BEAN), "IN",
                new BigDecimal("10"), "Revalued delivery", new BigDecimal("0.0300"), "STOCK_IN"));
        assertEquals(0, new BigDecimal("0.0300").compareTo(
                balances.find(store, UUID.fromString(COFFEE_BEAN)).orElseThrow().averageUnitCost()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void unknownIngredientAndBadTypeRejected() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore();
        assertThrows(ResourceNotFoundException.class, () -> service.adjust(admin, store,
                new AdjustInventoryRequest(UUID.randomUUID(), "IN", BigDecimal.ONE, "Ghost", null, null)));
        assertThrows(ValidationException.class,
                () -> service.adjust(admin, store, req("IN", "1", "x", "SALE_CONSUMPTION")));
        assertThrows(ValidationException.class, () -> service.adjust(admin, store, req("IN", "1", "x", "MAGIC")));
        SecurityContextHolder.clearContext();
    }

    @Test
    void movementsPagesNewestFirstWithFilters() {
        CurrentActor admin = authenticateAdmin();
        UUID store = seedStore();
        seedBalance(store, "100", "0");
        service.adjust(admin, store, req("IN", "10", "Delivery one", "STOCK_IN"));
        service.adjust(admin, store, req("OUT", "4", "Waste", "DAMAGED"));

        Page<StockMovementResponse> all = service.movements(store, null, null, 0, 20);
        assertEquals(2, all.getTotalElements());
        // Newest-first: the DAMAGED OUT line leads, with labels and running balances.
        StockMovementResponse first = all.getContent().get(0);
        assertEquals("DAMAGED", first.transactionType());
        assertEquals("Waste", first.reason());
        assertEquals("COFFEE_BEAN", first.ingredientCode());
        assertEquals(0, new BigDecimal("-4").compareTo(first.delta()));
        assertEquals(0, new BigDecimal("110").compareTo(first.balanceBefore()));
        assertEquals(0, new BigDecimal("106").compareTo(first.balanceAfter()));
        assertTrue(first.occurredAt().isAfter(Instant.EPOCH));

        Page<StockMovementResponse> onlyIn = service.movements(store, "STOCK_IN", null, 0, 20);
        assertEquals(1, onlyIn.getTotalElements());
        assertEquals("Delivery one", onlyIn.getContent().get(0).reason());

        assertEquals(0, service.movements(store, "ADJUSTMENT", null, 0, 20).getTotalElements());
        assertThrows(ValidationException.class, () -> service.movements(store, "NOPE", null, 0, 20));
        assertEquals(100, service.movements(store, null, null, 0, 5000).getSize(), "page size bounded");
        SecurityContextHolder.clearContext();
    }
}
