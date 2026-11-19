package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.api.*;
import com.smartcoffee.modules.inventory.application.IngredientCommandService;
import com.smartcoffee.modules.inventory.application.ReorderActionService;
import com.smartcoffee.modules.inventory.application.ReorderQueryService;
import com.smartcoffee.modules.inventory.application.UnitOfMeasureCommandService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reorder planning + ingredient/UOM master data (V036/V037) against a real
 * migrated database. Verifies the low-stock scan keys off the reorder point
 * (only balances at/below it surface, unset plans never do), the suggestion math
 * sizes back to the ceiling, drafting a reorder produces a DRAFT purchase order,
 * and the ingredient / unit master editor enforces code uniqueness + the
 * optimistic-lock version echo. This is the read-only/management half of M2 —
 * no stock mutation is asserted here because none happens on these paths.
 */
class ReorderIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    ReorderQueryService reorderQueries;
    @Autowired
    ReorderActionService reorderActions;
    @Autowired
    IngredientCommandService ingredientCommands;
    @Autowired
    UnitOfMeasureCommandService uomCommands;

    private static final String COFFEE = "70000000-0000-0000-0000-000000000001";
    private static final String MILK = "70000000-0000-0000-0000-000000000002";
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private CurrentActor admin() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Reorder Admin", "ro-" + userId + "@smart.coffee", "ro-" + userId + "@smart.coffee", "en-US");
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
                + "values (?::uuid, ?, 'Reorder Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'km-KH', true, now(), "
                + "now(), 0)", storeId.toString(), "RO" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return storeId;
    }

    private void seedBalance(UUID store, String ingredient, String current, String reorderPoint, String maxStock) {
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id) values (?::uuid, ?::uuid) "
                + "on conflict (store_id, ingredient_id) do nothing", store.toString(), ingredient);
        jdbc.update("update inventory.inventory_balances set current_stock=?::numeric, reserved_stock=0, "
                + "reorder_point=?::numeric, max_stock=?::numeric where store_id=?::uuid and ingredient_id=?::uuid",
                current, reorderPoint, maxStock, store.toString(), ingredient);
    }

    private UUID seedSupplier() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.suppliers (supplier_id, supplier_code, supplier_name, active, created_at, "
                + "updated_at, version) values (?::uuid, ?, 'Reorder Supplier', true, now(), now(), 0)", id.toString(),
                "ROS" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID anyUnitId() {
        return jdbc.queryForObject("select unit_id from inventory.units_of_measure order by unit_id limit 1",
                UUID.class);
    }

    @Test
    void lowStockAndSuggestionsReflectReorderPoint() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        // COFFEE is low (available 5 <= reorder 10); MILK has an unset plan (0) so it never surfaces.
        seedBalance(store, COFFEE, "5", "10", "100");
        seedBalance(store, MILK, "3", "0", "0");

        List<LowStockResponse> low = reorderQueries.lowStock(admin, store);
        assertEquals(1, low.size());
        assertEquals(UUID.fromString(COFFEE), low.get(0).ingredientId());
        assertEquals(0, new BigDecimal("5").compareTo(low.get(0).availableStock()));

        List<ReorderSuggestionResponse> suggestions = reorderQueries.reorderSuggestions(admin, store);
        assertEquals(1, suggestions.size());
        // suggested = max(100) - current(5) = 95.
        assertEquals(0, new BigDecimal("95").compareTo(suggestions.get(0).suggestedQuantity()));
        SecurityContextHolder.clearContext();
    }

    @Test
    void reorderDraftsPurchaseOrder() {
        CurrentActor admin = admin();
        UUID store = seedStore();
        seedBalance(store, COFFEE, "5", "10", "100");
        UUID supplier = seedSupplier();

        var draft = reorderActions.draftPurchaseOrder(admin, store, new RequestReorderRequest(supplier, null, "restock",
                List.of(new RequestReorderRequest.Line(UUID.fromString(COFFEE), new BigDecimal("95"), null))));
        assertEquals("DRAFT", draft.status());
        assertEquals(1, draft.lines().size());
        assertEquals(0, new BigDecimal("95").compareTo(draft.lines().get(0).quantityOrdered()));
        // No stock moved: drafting is advisory until the PO is submitted/received.
        assertEquals(0, new BigDecimal("5").compareTo(
                jdbc.queryForObject("select current_stock from inventory.inventory_balances where store_id=?::uuid "
                        + "and ingredient_id=?::uuid", BigDecimal.class, store.toString(), COFFEE)));
        SecurityContextHolder.clearContext();
    }

    @Test
    void ingredientMasterCreateDuplicateAndVersionConflict() {
        CurrentActor admin = admin();
        UUID unit = anyUnitId();
        String code = "ING" + UUID.randomUUID().toString().substring(0, 6);
        IngredientResponse created = ingredientCommands.create(admin, new CreateIngredientRequest(code, "Syrup", unit,
                new BigDecimal("1.50"), true, false, null, true));
        assertEquals(code, created.code());
        assertFalse(created.trackBatches());

        ConflictException dup = assertThrows(ConflictException.class, () -> ingredientCommands.create(admin,
                new CreateIngredientRequest(code, "Syrup Two", unit, BigDecimal.ONE, true, false, null, true)));
        assertEquals("DUPLICATE_CODE", dup.code());

        ConflictException stale = assertThrows(ConflictException.class, () -> ingredientCommands.update(admin,
                created.ingredientId(), new UpdateIngredientRequest(code, "Renamed", unit, new BigDecimal("1.50"),
                        true, true, 30, true, 99L)));
        assertEquals("VERSION_CONFLICT", stale.code());

        IngredientResponse updated = ingredientCommands.update(admin, created.ingredientId(),
                new UpdateIngredientRequest(code, "Renamed", unit, new BigDecimal("1.75"), true, true, 30, true,
                        created.version()));
        assertEquals("Renamed", updated.name());
        assertTrue(updated.trackBatches());
        assertEquals(30, updated.shelfLifeDays());
        assertEquals(created.version() + 1, updated.version());
        SecurityContextHolder.clearContext();
    }

    @Test
    void uomMasterCreateAndDuplicate() {
        CurrentActor admin = admin();
        String code = "U" + UUID.randomUUID().toString().substring(0, 6);
        UnitManagementResponse created = uomCommands.create(admin, new CreateUnitRequest(code, "Tablespoon", "COUNT",
                0, new BigDecimal("15"), false, true));
        assertEquals(code, created.code());
        assertTrue(created.active());

        ConflictException dup = assertThrows(ConflictException.class, () -> uomCommands.create(admin,
                new CreateUnitRequest(code, "Tbsp", "COUNT", 0, BigDecimal.ONE, false, true)));
        assertEquals("DUPLICATE_CODE", dup.code());

        UnitManagementResponse updated = uomCommands.update(admin, created.unitId(), new UpdateUnitRequest(code,
                "Soup spoon", "COUNT", 1, new BigDecimal("15"), false, false, created.version()));
        assertEquals("Soup spoon", updated.name());
        assertFalse(updated.active());
        assertEquals(created.version() + 1, updated.version());
        SecurityContextHolder.clearContext();
    }
}
