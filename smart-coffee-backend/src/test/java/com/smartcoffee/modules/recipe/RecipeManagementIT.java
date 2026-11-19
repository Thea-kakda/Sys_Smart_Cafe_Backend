package com.smartcoffee.modules.recipe;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import com.smartcoffee.modules.catalog.api.request.CreateProductVariantRequest;
import com.smartcoffee.modules.catalog.application.ProductVariantCommandService;
import com.smartcoffee.modules.recipe.api.ProductRecipeResponse;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.ResolvedRecipeResponse;
import com.smartcoffee.modules.recipe.api.VariantRecipeResponse;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.AssignVariantRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.CreateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.api.request.RecipeItemRequest;
import com.smartcoffee.modules.recipe.api.request.UpdateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.application.RecipeAssignmentService;
import com.smartcoffee.modules.recipe.application.RecipeCommandService;
import com.smartcoffee.modules.recipe.application.RecipeQueryService;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Source;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Slice 2A/2B.1 end-to-end verification against a fresh Testcontainers Postgres
 * (Spring context + Flyway V001 → V044 + JPA + permissions + services). Mirrors
 * {@code VariantAndEffectIT}: drives the real application services the
 * controllers use; the exception types map 1:1 to the HTTP statuses required by
 * the spec (Forbidden→403, ResourceNotFound→404, Conflict→409, Validation→400).
 *
 * <p>Covers: migration application + legacy backfill, lifecycle (DRAFT /
 * ACTIVE-as-assignable / ARCHIVED; multiple ACTIVE versions may coexist; V044
 * dropped uk_recipe_one_active_per_product), explicit ASSIGNMENT pointers
 * (product fallback + variant override) as the only selectors, optimistic
 * locking, and resolver precedence VARIANT → PRODUCT → NONE.
 */
class RecipeManagementIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String CASHIER = "00000000-0000-0000-0000-000000000005";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired RecipeCommandService commands;
    @Autowired RecipeQueryService queries;
    @Autowired RecipeAssignmentService assignment;
    @Autowired ProductVariantCommandService variantCommands;

    private CurrentActor admin;

    @BeforeEach
    void authenticateAdmin() {
        admin = seedActor(SUPER_ADMIN);
    }

    // ------------------------------------------------------------- migrations

    @Test
    void newMigrationsAreAppliedAndRecorded() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where version in ('042','043','044') and success = true "
                        + "order by version", String.class);
        assertTrue(versions.contains("042"), "V042 must be recorded");
        assertTrue(versions.contains("043"), "V043 must be recorded");
        assertTrue(versions.contains("044"), "V044 must be recorded");
    }

    @Test
    void priorMigrationsChecksumsAreUnchanged() {
        // Proves V039/V040/V041 were not edited (checksum is content-derived).
        assertEquals(976458884, checksum("039"));
        assertEquals(492324848, checksum("040"));
        assertEquals(-343098371, checksum("041"));
    }

    private int checksum(String version) {
        Integer c = jdbc.queryForObject(
                "select checksum from flyway_schema_history where version = ?", Integer.class, version);
        return c == null ? Integer.MIN_VALUE : c;
    }

    @Test
    void newColumnsAndIndexExist() {
        for (String col : List.of("yield_quantity", "yield_unit_id", "notes", "activated_at", "activated_by",
                "created_by", "updated_by", "updated_at")) {
            assertEquals(1, countColumn("recipe", "recipe_versions", col), "recipe_versions." + col);
        }
        for (String col : List.of("unit_id", "scaling_rule", "display_order", "optional", "notes")) {
            assertEquals(1, countColumn("recipe", "recipe_version_lines", col), "recipe_version_lines." + col);
        }
        assertEquals(1, countColumn("catalog", "product_variants", "active_recipe_version_id"));
        // Slice 2B.1: the product fallback pointer exists and the incompatible
        // one-ACTIVE-per-product selector index is GONE (V044 dropped it).
        assertEquals(1, countColumn("catalog", "products", "active_recipe_version_id"));
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname='catalog' and indexname='ix_product_active_recipe'",
                Integer.class));
        assertEquals(0, (int) jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname='uk_recipe_one_active_per_product'",
                Integer.class), "V044 must have dropped the one-ACTIVE selector index");
    }

    @Test
    void legacySeededRecipeIsPreservedAndStillResolves() {
        // Seeded (pre-2A) ACTIVE versions have no activated_by (V042 added the column as NULL).
        Integer seeded = jdbc.queryForObject(
                "select count(*) from recipe.recipe_versions where status='ACTIVE' and activated_by is null",
                Integer.class);
        assertNotNull(seeded);
        assertTrue(seeded >= 1, "expected at least one legacy seeded ACTIVE version");

        // V042 backfilled yield to 1 for every pre-existing version.
        Integer nonUnitYield = jdbc.queryForObject(
                "select count(*) from recipe.recipe_versions where activated_by is null and yield_quantity <> 1",
                Integer.class);
        assertEquals(0, nonUnitYield, "legacy versions must be backfilled to yield_quantity = 1");

        // Lines keep positive quantities and deterministic display_order after backfill.
        Integer badQty = jdbc.queryForObject(
                "select count(*) from recipe.recipe_version_lines where quantity_required_base <= 0", Integer.class);
        assertEquals(0, badQty);

        UUID productId = jdbc.queryForObject(
                "select product_id from recipe.recipe_versions where status='ACTIVE' and activated_by is null limit 1",
                UUID.class);
        ResolvedRecipeResponse resolved = queries.resolveProduct(admin, productId);
        assertEquals(Source.PRODUCT, resolved.source(), "legacy product recipe must still resolve for checkout");
        // V044 backfilled the explicit pointer from the prior single ACTIVE version.
        UUID backfilled = jdbc.queryForObject(
                "select p.active_recipe_version_id from catalog.products p "
                        + "join recipe.recipe_versions rv on rv.recipe_version_id = p.active_recipe_version_id "
                        + "where p.product_id = ?", UUID.class, productId);
        assertEquals(resolved.recipeVersion().recipeVersionId(), backfilled,
                "backfilled pointer must equal the resolved legacy ACTIVE version");
    }

    @Test
    void multipleActiveVersionsMayNowCoexistPerProduct() {
        // The Slice 2B.1 correction: V044 dropped uk_recipe_one_active_per_product,
        // so the SAME product can own a fallback version and a distinct variant
        // override version, both ACTIVE (assignable), simultaneously.
        UUID productId = seedProduct("COEX_" + suffix());
        UUID versionA = insertRawVersion(productId, 1, "ACTIVE");
        UUID versionB = insertRawVersion(productId, 2, "ACTIVE");
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from recipe.recipe_versions where product_id=? and status='ACTIVE'",
                Integer.class, productId), "both ACTIVE versions must persist side by side");
        assertNotNull(versionA);
        assertNotNull(versionB);
    }

    @Test
    void recipelessProductIsNotForcedToHaveARecipe() {
        UUID productId = seedProduct("NO_RECIPE_" + suffix());
        Integer versions = jdbc.queryForObject(
                "select count(*) from recipe.recipe_versions where product_id = ?", Integer.class, productId);
        assertEquals(0, versions, "migration must not synthesize recipes");
        assertEquals(Source.NONE, queries.resolveProduct(admin, productId).source());
    }

    // ---------------------------------------------------------- permissions

    @Test
    void recipePermissionsSeededAndGranted() {
        for (String code : List.of("recipe:view", "recipe:manage", "recipe:activate", "recipe:assign")) {
            assertEquals(1L, jdbc.queryForObject(
                    "select count(*) from identity.permissions where permission_code = ?", Long.class, code));
            for (String role : List.of("SUPER_ADMIN", "ADMIN", "STORE_MANAGER")) {
                assertEquals(1L, jdbc.queryForObject(
                        "select count(*) from identity.role_permissions rp "
                                + "join identity.roles r on r.role_id=rp.role_id "
                                + "join identity.permissions p on p.permission_id=rp.permission_id "
                                + "where r.role_code=? and p.permission_code=?", Long.class, role, code),
                        role + " must hold " + code);
            }
        }
    }

    @Test
    void cashierCannotManageRecipes() {
        UUID productId = seedProduct("AUTHZ_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        CurrentActor cashier = seedActor(CASHIER);
        assertThrows(ForbiddenException.class, () ->
                commands.createDraft(cashier, productId, draft(BigDecimal.ONE, ingredient)));
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    void activationPublishesWithoutSupersedingAnything() {
        // Slice 2B.1: publishing makes a version immutable + assignable. It does
        // NOT archive/supersede other versions and does NOT change resolution —
        // only assignment does. Both versions remain ACTIVE and readable.
        UUID productId = seedProduct("LC_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());

        RecipeVersionResponse v1 = commands.createDraft(admin, productId, draft(new BigDecimal("2"), ingredient));
        assertEquals(RecipeStatus.DRAFT, v1.status());
        assertEquals(1, v1.versionNumber());
        assertEquals(0, new BigDecimal("2").compareTo(v1.yieldQuantity()));

        RecipeVersionResponse a1 = commands.activate(admin, v1.recipeVersionId(), v1.version());
        assertEquals(RecipeStatus.ACTIVE, a1.status());
        assertNotNull(a1.activatedAt());
        assertEquals(admin.userId(), a1.activatedBy());

        RecipeVersionResponse v2 = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        assertEquals(2, v2.versionNumber());
        RecipeVersionResponse a2 = commands.activate(admin, v2.recipeVersionId(), v2.version());
        assertEquals(RecipeStatus.ACTIVE, a2.status());

        RecipeVersionResponse old = queries.getVersion(admin, v1.recipeVersionId());
        assertEquals(RecipeStatus.ACTIVE, old.status(), "publishing v2 must leave v1 published and readable");
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from recipe.recipe_versions where product_id=? and status='ACTIVE'",
                Integer.class, productId), "two assignable versions coexist");
        // Publishing changed nothing about resolution: no pointer was set.
        assertEquals(Source.NONE, queries.resolveProduct(admin, productId).source());
    }

    @Test
    void activeVersionIsImmutableOnEdit() {
        UUID productId = seedProduct("IMMUT_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        commands.activate(admin, d.recipeVersionId(), d.version());
        ConflictException ex = assertThrows(ConflictException.class, () ->
                commands.updateDraft(admin, d.recipeVersionId(),
                        new UpdateRecipeDraftRequest(d.version() + 1, new BigDecimal("5"), null, "x")));
        assertEquals("RECIPE_VERSION_IMMUTABLE", ex.code());
    }

    @Test
    void emptyDraftCannotActivate() {
        UUID productId = seedProduct("EMPTY_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "no lines", List.of()));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                commands.activate(admin, d.recipeVersionId(), d.version()));
        assertEquals("RECIPE_VERSION_INVALID", ex.code());
    }

    @Test
    void staleVersionRejectedOnUpdate() {
        UUID productId = seedProduct("STALE_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        commands.updateDraft(admin, d.recipeVersionId(),
                new UpdateRecipeDraftRequest(d.version(), new BigDecimal("3"), null, "ok"));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                commands.updateDraft(admin, d.recipeVersionId(),
                        new UpdateRecipeDraftRequest(d.version(), new BigDecimal("4"), null, "stale")));
        assertEquals("RECIPE_VERSION_CONFLICT", ex.code());
    }

    @Test
    void twoActiveVersionsCoexistAfterTheSelectorIndexIsGone() {
        // Direct proof of the corrected model: the V043 contradiction (a second
        // ACTIVE row violating uk_recipe_one_active_per_product) is gone.
        UUID productId = seedProduct("IDX_" + suffix());
        UUID versionA = insertRawVersion(productId, 1, "ACTIVE");
        UUID versionB = insertRawVersion(productId, 2, "ACTIVE");
        assertNotEquals(versionA, versionB);
    }

    // ------------------------------------------------------------- assignment

    @Test
    void assignActiveSameProductThenResolveVariant() {
        UUID productId = seedProduct("ASSIGN_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        RecipeVersionResponse active = commands.activate(admin, d.recipeVersionId(), d.version());
        UUID variantId = createVariant(productId);

        VariantRecipeResponse assigned = assignment.assign(admin, variantId,
                new AssignVariantRecipeRequest(active.recipeVersionId(), variantVersion(variantId)));
        assertEquals(active.recipeVersionId(), assigned.activeRecipeVersionId());

        ResolvedRecipeResponse resolved = queries.resolveVariant(admin, productId, variantId);
        assertEquals(Source.VARIANT, resolved.source());
    }

    @Test
    void assignDraftRejected() {
        UUID productId = seedProduct("ASSIGN_D_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        UUID variantId = createVariant(productId);
        ValidationException ex = assertThrows(ValidationException.class, () ->
                assignment.assign(admin, variantId,
                        new AssignVariantRecipeRequest(d.recipeVersionId(), variantVersion(variantId))));
        assertEquals("RECIPE_VERSION_NOT_ACTIVE", ex.code());
    }

    @Test
    void assignOtherProductRejected() {
        UUID productA = seedProduct("ASG_A_" + suffix());
        UUID productB = seedProduct("ASG_B_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productA, draft(BigDecimal.ONE, ingredient));
        commands.activate(admin, d.recipeVersionId(), d.version());
        UUID variantB = createVariant(productB);
        ValidationException ex = assertThrows(ValidationException.class, () ->
                assignment.assign(admin, variantB,
                        new AssignVariantRecipeRequest(d.recipeVersionId(), variantVersion(variantB))));
        assertEquals("VARIANT_RECIPE_PRODUCT_MISMATCH", ex.code());
    }

    @Test
    void assignMissingVariantRejected() {
        assertThrows(ResourceNotFoundException.class, () ->
                assignment.assign(admin, UUID.randomUUID(), new AssignVariantRecipeRequest(UUID.randomUUID(), 0L)));
    }

    @Test
    void assignProductFallbackThenResolveProduct() {
        // 2B.1: the product pointer IS the fallback selector — assignment, not status.
        UUID productId = seedProduct("PRD_ASSIGN_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        RecipeVersionResponse active = commands.activate(admin, d.recipeVersionId(), d.version());
        // Published but unassigned: nothing resolves.
        assertEquals(Source.NONE, queries.resolveProduct(admin, productId).source());

        ProductRecipeResponse assigned = assignment.assignProduct(admin, productId,
                new AssignProductRecipeRequest(active.recipeVersionId(), productVersion(productId)));
        assertEquals(active.recipeVersionId(), assigned.activeRecipeVersionId());

        ResolvedRecipeResponse resolved = queries.resolveProduct(admin, productId);
        assertEquals(Source.PRODUCT, resolved.source());
        assertEquals(active.recipeVersionId(), resolved.recipeVersion().recipeVersionId());
        assertEquals(1, resolved.recipeVersion().lines().size());
    }

    @Test
    void assignProductDraftRejected() {
        UUID productId = seedProduct("PRD_D_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                assignment.assignProduct(admin, productId,
                        new AssignProductRecipeRequest(d.recipeVersionId(), productVersion(productId))));
        assertEquals("RECIPE_VERSION_NOT_ACTIVE", ex.code());
    }

    @Test
    void assignProductCrossProductRejected() {
        UUID productA = seedProduct("PRD_A_" + suffix());
        UUID productB = seedProduct("PRD_B_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productA, draft(BigDecimal.ONE, ingredient));
        commands.activate(admin, d.recipeVersionId(), d.version());
        ValidationException ex = assertThrows(ValidationException.class, () ->
                assignment.assignProduct(admin, productB,
                        new AssignProductRecipeRequest(d.recipeVersionId(), productVersion(productB))));
        assertEquals("PRODUCT_RECIPE_MISMATCH", ex.code());
    }

    @Test
    void assignProductMissingRejected() {
        assertThrows(ResourceNotFoundException.class, () ->
                assignment.assignProduct(admin, UUID.randomUUID(),
                        new AssignProductRecipeRequest(UUID.randomUUID(), 0L)));
    }

    @Test
    void staleProductVersionRejectedOnAssignment() {
        UUID productId = seedProduct("PRD_STALE_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        RecipeVersionResponse active = commands.activate(admin, d.recipeVersionId(), d.version());
        ConflictException ex = assertThrows(ConflictException.class, () ->
                assignment.assignProduct(admin, productId,
                        new AssignProductRecipeRequest(active.recipeVersionId(), 999L)));
        assertEquals("PRODUCT_VERSION_CONFLICT", ex.code());
    }

    @Test
    void recipeBasedProductCannotClearFallback() {
        UUID productId = seedProduct("PRD_RB_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        jdbc.update("update catalog.products set stock_mode = 'RECIPE_BASED' where product_id = ?", productId);
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        RecipeVersionResponse active = commands.activate(admin, d.recipeVersionId(), d.version());
        assignment.assignProduct(admin, productId,
                new AssignProductRecipeRequest(active.recipeVersionId(), productVersion(productId)));
        ValidationException ex = assertThrows(ValidationException.class, () ->
                assignment.assignProduct(admin, productId,
                        new AssignProductRecipeRequest(null, productVersion(productId))));
        assertEquals("PRODUCT_RECIPE_REQUIRED", ex.code());
        // The refusal changed nothing: the fallback pointer is still in place.
        assertEquals(Source.PRODUCT, queries.resolveProduct(admin, productId).source());
    }

    @Test
    void cashierCannotAssignProductRecipe() {
        UUID productId = seedProduct("PRD_AUTHZ_" + suffix());
        CurrentActor cashier = seedActor(CASHIER);
        assertThrows(ForbiddenException.class, () ->
                assignment.assignProduct(cashier, productId,
                        new AssignProductRecipeRequest(UUID.randomUUID(), productVersion(productId))));
    }

    @Test
    void staleVariantVersionRejected() {
        UUID productId = seedProduct("ASG_STALE_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        RecipeVersionResponse active = commands.activate(admin, d.recipeVersionId(), d.version());
        UUID variantId = createVariant(productId);
        ConflictException ex = assertThrows(ConflictException.class, () ->
                assignment.assign(admin, variantId,
                        new AssignVariantRecipeRequest(active.recipeVersionId(), 999L)));
        assertEquals("VARIANT_VERSION_CONFLICT", ex.code());
    }

    @Test
    void removeOverrideRestoresProductFallback() {
        UUID productId = seedProduct("ASG_CLR_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        RecipeVersionResponse active = commands.activate(admin, d.recipeVersionId(), d.version());
        assignment.assignProduct(admin, productId,
                new AssignProductRecipeRequest(active.recipeVersionId(), productVersion(productId)));
        UUID variantId = createVariant(productId);
        assignment.assign(admin, variantId,
                new AssignVariantRecipeRequest(active.recipeVersionId(), variantVersion(variantId)));
        assertEquals(Source.VARIANT, queries.resolveVariant(admin, productId, variantId).source());

        VariantRecipeResponse cleared = assignment.assign(admin, variantId,
                new AssignVariantRecipeRequest(null, variantVersion(variantId)));
        assertNull(cleared.activeRecipeVersionId());
        assertEquals(Source.PRODUCT, queries.resolveVariant(admin, productId, variantId).source());
    }

    @Test
    void archiveBlockedWhileAssignedThenAllowedAfterReassignment() {
        // 2B.1 archive rule: a version referenced by a product fallback OR a
        // variant override cannot be archived (RECIPE_VERSION_IN_USE); nothing
        // is ever silently unassigned. Unassigned versions archive fine.
        UUID productId = seedProduct("ARC_" + suffix());
        UUID ingredient = seedIngredient("REC_ING_" + suffix());
        RecipeVersionResponse d = commands.createDraft(admin, productId, draft(BigDecimal.ONE, ingredient));
        RecipeVersionResponse active = commands.activate(admin, d.recipeVersionId(), d.version());

        // Variant override pins it first.
        UUID variantId = createVariant(productId);
        assignment.assign(admin, variantId,
                new AssignVariantRecipeRequest(active.recipeVersionId(), variantVersion(variantId)));
        ConflictException ex = assertThrows(ConflictException.class, () ->
                commands.archive(admin, active.recipeVersionId(), active.version()));
        assertEquals("RECIPE_VERSION_IN_USE", ex.code());

        // Product fallback pins it too, even after the variant is cleared.
        assignment.assign(admin, variantId, new AssignVariantRecipeRequest(null, variantVersion(variantId)));
        assignment.assignProduct(admin, productId,
                new AssignProductRecipeRequest(active.recipeVersionId(), productVersion(productId)));
        ConflictException ex2 = assertThrows(ConflictException.class, () ->
                commands.archive(admin, active.recipeVersionId(), queries.getVersion(admin, active.recipeVersionId()).version()));
        assertEquals("RECIPE_VERSION_IN_USE", ex2.code());

        // Unassign (Make-to-order product may drop its fallback), then archive.
        assignment.assignProduct(admin, productId,
                new AssignProductRecipeRequest(null, productVersion(productId)));
        RecipeVersionResponse archived = commands.archive(admin, active.recipeVersionId(),
                queries.getVersion(admin, active.recipeVersionId()).version());
        assertEquals(RecipeStatus.ARCHIVED, archived.status(), "unassigned versions archive");
        assertEquals(RecipeStatus.ARCHIVED, queries.getVersion(admin, active.recipeVersionId()).status(),
                "archive must be durable, not a projection artifact");
    }

    // ------------------------------------------------------------- helpers

    private CreateRecipeDraftRequest draft(BigDecimal yield, UUID ingredient) {
        RecipeItemRequest item = new RecipeItemRequest(ingredient, new BigDecimal("0.25"),
                BigDecimal.ZERO, null, RecipeScalingRule.FIXED, 0, false, null);
        return new CreateRecipeDraftRequest(yield, null, "IT draft", List.of(item));
    }

    private UUID createVariant(UUID productId) {
        ProductVariantResponse v = variantCommands.create(admin, productId,
                new CreateProductVariantRequest("VAR_" + suffix(), "Variant", null, null,
                        new BigDecimal("10"), null, "USD", false, true, 0, null));
        return v.variantId();
    }

    private long variantVersion(UUID variantId) {
        return jdbc.queryForObject("select version from catalog.product_variants where variant_id = ?",
                Long.class, variantId);
    }

    private long productVersion(UUID productId) {
        return jdbc.queryForObject("select version from catalog.products where product_id = ?",
                Long.class, productId);
    }

    private UUID insertRawVersion(UUID productId, int versionNumber, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into recipe.recipe_versions (recipe_version_id, product_id, version_number, status, "
                + "effective_from, created_at, updated_at, yield_quantity, version) values (?,?,?,?, now(), now(), now(), 1, 0)",
                id, productId, versionNumber, status);
        return id;
    }

    private int countColumn(String schema, String table, String column) {
        return jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema=? and table_name=? and column_name=?",
                Integer.class, schema, table, column);
    }

    private UUID seedProduct(String code) {
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
                + "standard_cost, track_inventory, track_batches, active, created_at, updated_at, version) "
                + "values (?,?,?,?, 0, false, false, true, now(), now(), 0)",
                id, code + "_" + suffix(), code, uom);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID userId = UUID.randomUUID();
        String email = "recipe-it-" + userId + "@smart.coffee";
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
