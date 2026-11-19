package com.smartcoffee.modules.availability;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import com.smartcoffee.modules.catalog.api.request.CreateProductVariantRequest;
import com.smartcoffee.modules.catalog.application.ProductVariantCommandService;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.AssignVariantRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.CreateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.api.request.RecipeItemRequest;
import com.smartcoffee.modules.recipe.application.RecipeAssignmentService;
import com.smartcoffee.modules.recipe.application.RecipeCommandService;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Shared raw-seeding scaffolding for the Slice 3A availability integration tests. It
 * reuses the proven seeding path of {@code ReservationOpsTestBase} / {@code VariantAndEffectIT}
 * against the same migrated Testcontainers Postgres (real Flyway V001..V047, real JPA
 * command services), and layers the read-only availability engine on top so each scenario
 * reads as "arrange genuine catalog/inventory state, call the engine, assert the verdict".
 *
 * <p>Everything here is WRITE-side arrangement only; the tests assert the availability
 * engine never mutates any of it (spec §26).
 */
abstract class AvailabilityITBase extends IntegrationTestBase {

    protected static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    protected static final String STORE_MANAGER = "00000000-0000-0000-0000-000000000004";
    protected static final String CUSTOMER = "00000000-0000-0000-0000-000000000007";
    protected static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    protected static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    protected static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected CurrentActorProvider actors;
    @Autowired protected AvailabilityService availability;
    @Autowired protected AvailabilityManagementService management;
    @Autowired protected RecipeCommandService recipeCommands;
    @Autowired protected RecipeAssignmentService assignment;
    @Autowired protected ProductVariantCommandService variantCommands;

    protected UUID storeId;

    // ------------------------------------------------------------------ store

    protected UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Avail Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "AV" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        return id;
    }

    // ---------------------------------------------------------------- product

    /** A MADE_TO_ORDER product (DB-default stock mode) with a fresh unique code. */
    protected UUID seedProduct(String code) {
        return seedProduct(code, "MADE_TO_ORDER", true);
    }

    protected UUID seedProduct(String code, String stockMode, boolean active) {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, stock_mode, created_at, "
                + "updated_at, version) values (?,?,?,?,?, 'USD', 5, 0.1, true, ?, ?, now(), now(), 0)",
                id, code, category, "IT " + code, dec("10"), active, stockMode);
        return id;
    }

    protected void offerProduct(UUID store, UUID productId) {
        offerProduct(store, productId, true);
    }

    protected void offerProduct(UUID store, UUID productId, boolean available) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,?,now(),0)", store, productId, available);
    }

    protected void setStoreAvailable(UUID store, UUID productId, boolean available) {
        jdbc.update("update catalog.store_products set available = ?, updated_at = now(), version = version + 1 "
                + "where store_id = ? and product_id = ?", available, store, productId);
    }

    // ------------------------------------------------------------- ingredients

    protected UUID seedIngredient(String code, UUID baseUnit) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, "
                + "base_unit_id, standard_cost, track_inventory, track_batches, active, created_at, updated_at, "
                + "version) values (?,?,?,?,0,true,false,true,now(),now(),0)", id, code + "_" + suffix(), code, baseUnit);
        return id;
    }

    protected void seedBalance(UUID store, UUID ingredient, String current) {
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id, current_stock, "
                + "reserved_stock, minimum_stock, average_unit_cost) values (?,?,?,0,0,0.025)", store, ingredient,
                dec(current));
    }

    protected void setBalance(UUID store, UUID ingredient, String current, String reserved) {
        jdbc.update("update inventory.inventory_balances set current_stock = ?, reserved_stock = ?, version = "
                + "version + 1 where store_id = ? and ingredient_id = ?", dec(current), dec(reserved), store, ingredient);
    }

    // ----------------------------------------------------------------- recipe

    /** Creates + activates a product-owned recipe version; returns its id (unassigned). */
    protected UUID activeRecipe(UUID productId, String name, List<RecipeItemRequest> items) {
        CurrentActor admin = seedActor(SUPER_ADMIN);
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, name, items));
        RecipeVersionResponse active = recipeCommands.activate(admin, draft.recipeVersionId(), draft.version());
        return active.recipeVersionId();
    }

    /** Creates + activates + assigns a product-level fallback recipe. */
    protected void assignProductRecipe(UUID productId, String name, List<RecipeItemRequest> items) {
        CurrentActor admin = seedActor(SUPER_ADMIN);
        UUID rvId = activeRecipe(productId, name, items);
        long productVersion = jdbc.queryForObject(
                "select version from catalog.products where product_id = ?", Long.class, productId);
        assignment.assignProduct(admin, productId, new AssignProductRecipeRequest(rvId, productVersion));
    }

    protected void assignVariantRecipe(ProductVariantResponse variant, UUID productId, UUID recipeVersionId) {
        CurrentActor admin = seedActor(SUPER_ADMIN);
        assignment.assign(admin, variant.variantId(),
                new AssignVariantRecipeRequest(recipeVersionId, variant.version()));
    }

    protected ProductVariantResponse createVariant(UUID productId, String code, boolean isDefault) {
        CurrentActor admin = seedActor(SUPER_ADMIN);
        return variantCommands.create(admin, productId, new CreateProductVariantRequest(
                code, "Name-" + code, null, null, dec("10"), null, "USD", isDefault, true, 0, null));
    }

    // ------------------------------------------------------ customization space

    /** Inserts an active customization group linked to the product; returns its id. */
    protected UUID seedGroup(UUID productId, String groupCode, String groupName, String selectionType,
            int minimum, Integer maximum) {
        UUID groupId = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_groups (group_id, group_code, group_name, selection_type, "
                + "minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,?,?,true,now(),now(),0)",
                groupId, groupCode + "_" + suffix(), groupName, selectionType, minimum, maximum, 0);
        jdbc.update("insert into catalog.product_customization_groups (product_id, group_id, required, display_order) "
                + "values (?,?,?,0)", productId, groupId, minimum >= 1);
        return groupId;
    }

    /** Inserts an active option under a group with an optional inventory effect. */
    protected UUID seedOption(UUID groupId, String code, String name, BigDecimal price,
            String effectType, UUID source, UUID target, BigDecimal qty, BigDecimal mult) {
        UUID optionId = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_options (option_id, group_id, option_code, option_name, "
                + "additional_price, display_order, active, inventory_effect_type, source_ingredient_id, "
                + "target_ingredient_id, effect_quantity, effect_multiplier, created_at, updated_at, version) "
                + "values (?,?,?,?,?,0,true,?,?,?,?,?,now(),now(),0)",
                optionId, groupId, code + "_" + suffix(), name, price, effectType, source, target, qty, mult);
        return optionId;
    }

    // ------------------------------------------------------- reconciliation state

    /** Seeds an OPEN store-scoped CRITICAL finding for one ingredient (via a bare run). */
    protected void seedCriticalFinding(UUID store, UUID ingredient) {
        UUID runId = UUID.randomUUID();
        jdbc.update("insert into inventory.inventory_reconciliation_runs (run_id, store_id, status, dry_run, "
                + "started_at, completed_at, scanned_count, anomaly_count, auto_repairable_count, "
                + "manual_review_count, repaired_count, failed_repair_count, initiated_by, version) "
                + "values (?,?,'COMPLETED',true,now(),now(),1,1,0,1,0,0,null,0)", runId, store);
        jdbc.update("insert into inventory.inventory_reconciliation_findings (finding_id, run_id, store_id, "
                + "inventory_item_id, anomaly_type, severity, repairable, status, created_at, version) "
                + "values (?,?,?,?,'ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER','CRITICAL',false,'OPEN',now(),0)",
                UUID.randomUUID(), runId, store, ingredient);
    }

    // -------------------------------------------------------------------- actor

    protected CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "avail-it-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Avail IT User", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), id, UUID.fromString(roleId), null,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        return actors.require();
    }

    /** A store-scoped manager actor (for the store-isolation branch of the preview API). */
    protected CurrentActor seedManagerActor(UUID store) {
        UUID id = UUID.randomUUID();
        String email = "avail-it-mgr-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Avail IT Manager", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), id, UUID.fromString(STORE_MANAGER), store,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        return actors.require();
    }

    // ---------------------------------------------------------------- utilities

    protected static RecipeItemRequest item(UUID ingredient, String qty, int order) {
        return new RecipeItemRequest(ingredient, dec(qty), BigDecimal.ZERO, null, RecipeScalingRule.FIXED, order,
                false, null);
    }

    protected static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    protected static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
