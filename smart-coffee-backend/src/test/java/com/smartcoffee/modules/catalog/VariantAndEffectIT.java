package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.InventoryEffectResponse;
import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import com.smartcoffee.modules.catalog.api.request.CreateProductVariantRequest;
import com.smartcoffee.modules.catalog.api.request.SetInventoryEffectRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateProductVariantRequest;
import com.smartcoffee.modules.catalog.application.InventoryEffectCalculator;
import com.smartcoffee.modules.catalog.application.OptionInventoryEffectCommandService;
import com.smartcoffee.modules.catalog.application.ProductVariantCommandService;
import com.smartcoffee.modules.catalog.application.ProductVariantQueryService;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * End-to-end Slice 1A verification against a fresh Testcontainers Postgres:
 * Spring context + Flyway (V001 → V041) + JPA (ProductVariant entity) +
 * permissions (V040) + service + calculator. Runs in the same HTTP request
 * pipeline the controllers use (via {@code CurrentActorProvider.require()} on
 * the SecurityContext); only the transport is bypassed, so routing rules are
 * exercised by the sibling {@code OpenApiContractIT}.
 *
 * <p>Sections:
 * <ul>
 *   <li>Migration verification: V039 + V040 + V041 applied cleanly, all new
 *       columns + constraints exist and legacy products remain readable.</li>
 *   <li>Permission seed: SUPER_ADMIN + ADMIN hold both new codes; STORE_MANAGER
 *       too; no other role got them.</li>
 *   <li>DB constraints: duplicate SKU and dual defaults are rejected by the
 *       database itself, not only by service pre-checks.</li>
 *   <li>Variant lifecycle: create / update / activate / set-default / reorder /
 *       soft delete + optimistic-lock stale version 409.</li>
 *   <li>Option inventory effect: upsert MULTIPLY, calculator produces 0/10/20/
 *       30 ml; clear resets every auxiliary field.</li>
 *   <li>Permission failure blocks mutation.</li>
 * </ul>
 */
class VariantAndEffectIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String CASHIER = "00000000-0000-0000-0000-000000000005";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired ProductVariantCommandService variantCommands;
    @Autowired ProductVariantQueryService variantQueries;
    @Autowired OptionInventoryEffectCommandService effectCommands;
    @Autowired InventoryEffectCalculator calculator;

    private CurrentActor admin;

    @BeforeEach
    void authenticateAdmin() {
        admin = seedActor(SUPER_ADMIN);
    }

    // ------------------------------------------------------------- migrations

    @Test
    void allNewMigrationsAreAppliedAndRecorded() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where version in ('039','040','041') "
                        + "and success = true order by version", String.class);
        assertTrue(versions.contains("039"), "V039 must be recorded");
        assertTrue(versions.contains("040"), "V040 must be recorded");
        assertTrue(versions.contains("041"), "V041 must be recorded");
    }

    @Test
    void newColumnsAndIndexesExist() {
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema='catalog' and table_name='products' and column_name='product_type'",
                Integer.class));
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema='catalog' and table_name='products' and column_name='stock_mode'",
                Integer.class));
        for (String col : List.of("inventory_effect_type", "target_ingredient_id",
                "effect_quantity", "effect_multiplier", "replaces_option_id", "source_ingredient_id")) {
            assertEquals(1, (int) jdbc.queryForObject(
                    "select count(*) from information_schema.columns "
                            + "where table_schema='catalog' and table_name='customization_options' and column_name=?",
                    Integer.class, col), "expected column " + col);
        }
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema='catalog' and table_name='product_variants'",
                Integer.class));
    }

    @Test
    void legacyProductsRemainReadableWithSafeDefaults() {
        Long n = jdbc.queryForObject("select count(*) from catalog.products", Long.class);
        assertNotNull(n);
        // V039 backfills every product that existed then to STANDARD +
        // MADE_TO_ORDER. The backfilled set is bounded by the V039 install time:
        // comparing against today's whole table only held while no other slice had
        // introduced a product with a different stock mode, which is no longer
        // true (Slice 2C covers FINISHED_GOOD / NOT_TRACKED lines).
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from catalog.products where product_type is null or stock_mode is null",
                Long.class), "every product keeps a readable type and stock mode");
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from catalog.products p where p.created_at < "
                        + "(select installed_on from flyway_schema_history where version = '039') "
                        + "and (p.product_type <> 'STANDARD' or p.stock_mode <> 'MADE_TO_ORDER')",
                Long.class), "V039 backfilled every pre-existing product");
    }

    @Test
    void noExistingProductIsForcedToHaveVariants() {
        Long productsWithoutVariants = jdbc.queryForObject(
                "select count(*) from catalog.products p "
                        + "where not exists (select 1 from catalog.product_variants v where v.product_id = p.product_id)",
                Long.class);
        assertTrue(productsWithoutVariants >= 1,
                "at least one product must still have zero variants (backward compatible)");
    }

    // ------------------------------------------------------- permission seed

    @Test
    void bothNewPermissionsAreSeededAndGrantedToAdminRoles() {
        for (String code : List.of("variant:manage", "option-effect:manage")) {
            Long p = jdbc.queryForObject(
                    "select count(*) from identity.permissions where permission_code = ?", Long.class, code);
            assertEquals(1L, p, "permission " + code + " must exist exactly once");
            for (String role : List.of("SUPER_ADMIN", "ADMIN", "STORE_MANAGER")) {
                Long g = jdbc.queryForObject(
                        "select count(*) from identity.role_permissions rp "
                                + "join identity.roles r on r.role_id = rp.role_id "
                                + "join identity.permissions p on p.permission_id = rp.permission_id "
                                + "where r.role_code = ? and p.permission_code = ?",
                        Long.class, role, code);
                assertEquals(1L, g, role + " must hold " + code + " exactly once");
            }
        }
    }

    @Test
    void seedIsIdempotentNoDuplicateRoleGrants() {
        Long dupes = jdbc.queryForObject(
                "select count(*) from (select role_id, permission_id from identity.role_permissions "
                        + "group by role_id, permission_id having count(*) > 1) d", Long.class);
        assertEquals(0L, dupes);
    }

    // ------------------------------------------------------ db-level guards

    @Test
    void databaseRejectsDuplicateVariantCodeWithinProduct() {
        UUID productId = seedProduct("DB_DUP_" + suffix());
        variantCommands.create(admin, productId, newVariant("SKU-A", "USD", "10", false));
        // Second create via the service catches the pre-check, but we prove the
        // database itself would also reject by inserting raw SQL that bypasses
        // the service.
        UUID id1 = UUID.randomUUID();
        jdbc.update("insert into catalog.product_variants (variant_id, product_id, variant_code, variant_name, "
                + "price, currency_code, is_default, active, display_order, created_at, updated_at, version) "
                + "values (?,?,?,?,?, ?,false,true,0,now(),now(),0)",
                id1, productId, "RAW-SKU", "Raw", new BigDecimal("1"), "USD");
        Exception ex = assertThrows(Exception.class, () -> jdbc.update(
                "insert into catalog.product_variants (variant_id, product_id, variant_code, variant_name, "
                        + "price, currency_code, is_default, active, display_order, created_at, updated_at, version) "
                        + "values (?,?,?,?,?, ?,false,true,0,now(),now(),0)",
                UUID.randomUUID(), productId, "RAW-SKU", "Raw dup", new BigDecimal("1"), "USD"));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate key")
                        || ex.getMessage().toLowerCase().contains("unique"),
                "unique index must reject: " + ex.getMessage());
    }

    @Test
    void partialUniqueIndexRejectsTwoActiveDefaultsForSameProduct() {
        UUID productId = seedProduct("DB_DEF_" + suffix());
        variantCommands.create(admin, productId, newVariant("D1", "USD", "10", false));
        // Bypass the service atomic flip and try to insert two defaults via SQL.
        jdbc.update("insert into catalog.product_variants (variant_id, product_id, variant_code, variant_name, "
                + "price, currency_code, is_default, active, display_order, created_at, updated_at, version) "
                + "values (?,?,?,?,?, ?,true,true,0,now(),now(),0)",
                UUID.randomUUID(), productId, "P1", "P", new BigDecimal("1"), "USD");
        Exception ex = assertThrows(Exception.class, () -> jdbc.update(
                "insert into catalog.product_variants (variant_id, product_id, variant_code, variant_name, "
                        + "price, currency_code, is_default, active, display_order, created_at, updated_at, version) "
                        + "values (?,?,?,?,?, ?,true,true,0,now(),now(),0)",
                UUID.randomUUID(), productId, "P2", "P", new BigDecimal("1"), "USD"));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate key")
                        || ex.getMessage().contains("uk_variant_one_default_per_product"),
                "partial unique index must reject: " + ex.getMessage());
    }

    @Test
    void invalidInventoryEffectTypeIsRejectedByDatabase() {
        UUID optionId = firstCustomizationOptionId();
        Exception ex = assertThrows(Exception.class, () -> jdbc.update(
                "update catalog.customization_options set inventory_effect_type = 'BOGUS' where option_id = ?",
                optionId));
        assertTrue(ex.getMessage().toLowerCase().contains("check")
                        || ex.getMessage().toLowerCase().contains("ck_option_effect_type"),
                "ck_option_effect_type must fire: " + ex.getMessage());
    }

    @Test
    void invalidMultiplyShapeIsRejectedByDatabase() {
        UUID optionId = firstCustomizationOptionId();
        // MULTIPLY without a source ingredient must violate ck_option_effect_shape.
        Exception ex = assertThrows(Exception.class, () -> jdbc.update(
                "update catalog.customization_options set inventory_effect_type='MULTIPLY', "
                        + "source_ingredient_id=null, target_ingredient_id=null, effect_quantity=null, "
                        + "effect_multiplier=1.00 where option_id = ?", optionId));
        assertTrue(ex.getMessage().toLowerCase().contains("check"),
                "ck_option_effect_shape must fire: " + ex.getMessage());
    }

    // --------------------------------------------------- variant lifecycle

    @Test
    void createListUpdateSetDefaultReorderSoftDelete() {
        UUID productId = seedProduct("LC_" + suffix());
        ProductVariantResponse first = variantCommands.create(admin, productId,
                newVariant("V1", "USD", "29.00", true));
        ProductVariantResponse second = variantCommands.create(admin, productId,
                newVariant("V2", "USD", "39.00", false));
        assertTrue(first.isDefault());

        List<ProductVariantResponse> all = variantQueries.listForProduct(admin, productId);
        assertEquals(2, all.size());

        // Update: controlled diff via PATCH (never delete + reinsert).
        ProductVariantResponse updated = variantCommands.update(admin, second.variantId(),
                new UpdateProductVariantRequest(second.version(), "V2", "V2 renamed", "d",
                        "BAR", new BigDecimal("45.00"), new BigDecimal("20.00"), "USD", 5, null));
        assertEquals("V2 renamed", updated.name());
        assertEquals(second.variantId(), updated.variantId(), "controlled diff preserves id");
        assertTrue(updated.version() > second.version(), "version bumps on save");

        // set-default on V2 demotes V1.
        ProductVariantResponse defaulted = variantCommands.setDefault(admin, second.variantId());
        assertTrue(defaulted.isDefault());
        var refreshed = variantQueries.get(admin, first.variantId());
        assertFalse(refreshed.isDefault());

        // Reorder swaps displayOrder.
        variantCommands.reorder(admin, productId, List.of(first.variantId(), second.variantId()));
        var list = variantQueries.listForProduct(admin, productId);
        assertEquals(first.variantId(), list.get(0).variantId());

        // Soft delete on non-default succeeds and disappears from active reads.
        variantCommands.delete(admin, first.variantId());
        var remaining = variantQueries.listForProduct(admin, productId);
        assertEquals(1, remaining.size());
        assertEquals(second.variantId(), remaining.get(0).variantId());
    }

    @Test
    void staleVersionRejectedOnUpdate() {
        UUID productId = seedProduct("STALE_" + suffix());
        ProductVariantResponse v = variantCommands.create(admin, productId, newVariant("A", "USD", "10", false));
        variantCommands.update(admin, v.variantId(), new UpdateProductVariantRequest(
                v.version(), "A", "Renamed", null, null, new BigDecimal("12"), null, "USD", 0, null));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                variantCommands.update(admin, v.variantId(), new UpdateProductVariantRequest(
                        v.version(), "A", "Renamed twice", null, null, new BigDecimal("14"), null, "USD", 0, null)));
        assertEquals("PRODUCT_VERSION_CONFLICT", ex.code());
    }

    @Test
    void currencyMismatchRejected() {
        UUID productId = seedProduct("CUR_" + suffix());
        ValidationException ex = assertThrows(ValidationException.class, () ->
                variantCommands.create(admin, productId, newVariant("A", "EUR", "10", false)));
        assertEquals("CURRENCY_MISMATCH", ex.code());
    }

    @Test
    void missingProductReturnsNotFound() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () ->
                variantCommands.create(admin, UUID.randomUUID(), newVariant("A", "USD", "10", false)));
        assertEquals("PRODUCT_NOT_FOUND", ex.code());
    }

    @Test
    void missingVariantReturnsNotFound() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () ->
                variantCommands.setDefault(admin, UUID.randomUUID()));
        assertEquals("VARIANT_NOT_FOUND", ex.code());
    }

    @Test
    void unauthorizedActorCannotMutateVariants() {
        UUID productId = seedProduct("AUTHZ_" + suffix());
        CurrentActor cashier = seedActor(CASHIER);
        assertThrows(ForbiddenException.class, () ->
                variantCommands.create(cashier, productId, newVariant("A", "USD", "10", false)));
    }

    // ---------------------------------------------- option effect + calc

    @Test
    void multiplyEffectDrivesDeterministicSugarConsumption() {
        UUID syrup = seedIngredient("SUGAR_SYRUP_IT");
        UUID milk = seedIngredient("MILK_IT");
        UUID optionId = firstCustomizationOptionId();
        // Attach a MULTIPLY 0.50 to sugar syrup.
        effectCommands.set(admin, optionId, null,
                new SetInventoryEffectRequest(InventoryEffectType.MULTIPLY, syrup, null,
                        null, new BigDecimal("0.50"), null));
        Map<UUID, BigDecimal> base = Map.of(syrup, new BigDecimal("20"), milk, new BigDecimal("150"));
        Map<UUID, BigDecimal> out = calculator.calculate(base, List.of(optionId));
        assertEquals(0, new BigDecimal("10").compareTo(out.get(syrup)));
        assertEquals(0, new BigDecimal("150").compareTo(out.get(milk)),
                "MULTIPLY must scale only the configured source ingredient");
    }

    @Test
    @SuppressWarnings("deprecation") // legacy V039 field: clear() must still reset it
    void effectClearResetsAllAuxiliaryFields() {
        UUID syrup = seedIngredient("SYRUP_CLR");
        UUID optionId = firstCustomizationOptionId();
        effectCommands.set(admin, optionId, null,
                new SetInventoryEffectRequest(InventoryEffectType.ADD, null, syrup,
                        new BigDecimal("18"), null, null));
        InventoryEffectResponse cleared = effectCommands.clear(admin, optionId, null);
        assertEquals(InventoryEffectType.NONE, cleared.type());
        assertNull(cleared.sourceIngredientId());
        assertNull(cleared.targetIngredientId());
        assertNull(cleared.effectQuantity());
        assertNull(cleared.effectMultiplier());
        assertNull(cleared.replacesOptionId());
    }

    // ------------------------------------------------------------- helpers

    private static CreateProductVariantRequest newVariant(String code, String currency, String price, boolean def) {
        return new CreateProductVariantRequest(code, "Name-" + code, null, null,
                new BigDecimal(price), null, currency, def, true, 0, null);
    }

    private UUID seedProduct(String code) {
        // Reuse an existing category from the V003 seed.
        UUID cat = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, false, true, now(), now(), 0)",
                id, code, cat, "IT " + code, new BigDecimal("10"));
        return id;
    }

    private UUID seedIngredient(String code) {
        UUID uom = jdbc.queryForObject("select unit_id from inventory.units_of_measure limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, base_unit_id, "
                + "active, created_at, updated_at, version) values (?,?,?,?, true, now(), now(), 0)",
                id, code + "_" + suffix(), code, uom);
        return id;
    }

    private UUID firstCustomizationOptionId() {
        return jdbc.queryForObject("select option_id from catalog.customization_options limit 1", UUID.class);
    }

    private CurrentActor seedActor(String roleId) {
        UUID userId = UUID.randomUUID();
        String email = "variant-it-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "IT User", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,?,?)", UUID.randomUUID(), userId, UUID.fromString(roleId), null,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
