package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.infrastructure.storage.StorageException;
import com.smartcoffee.modules.catalog.api.ManagementCategoryResponse;
import com.smartcoffee.modules.catalog.api.ManagementProductResponse;
import com.smartcoffee.modules.catalog.api.request.CreateCategoryRequest;
import com.smartcoffee.modules.catalog.api.request.CreateProductRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCategoryRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateProductRequest;
import com.smartcoffee.modules.catalog.domain.ProductType;
import com.smartcoffee.modules.catalog.domain.StockMode;
import com.smartcoffee.modules.catalog.application.CategoryCommandService;
import com.smartcoffee.modules.catalog.application.CategoryManagementQueryService;
import com.smartcoffee.modules.catalog.application.ProductCommandService;
import com.smartcoffee.modules.catalog.application.ProductManagementQueryService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * End-to-end catalog write path against the Testcontainers Postgres + MinIO.
 * Proves the DB/ storage round trip the unit tests only mock: a product created
 * with a real uploaded object persists its {@code image_object_key} and the
 * management read returns a loadable presigned {@code imageUrl}; replacement
 * reclaims the previous object only after commit; and the permission, prefix,
 * uniqueness and optimistic-lock guards behave over the real transaction.
 *
 * <p>Fixtures (V002/V003): category COFFEE {@code 20000000-…-01}; roles
 * SUPER_ADMIN {@code …-0002} (holds product/category:manage via V032) and
 * CASHIER {@code …-0005} (holds neither).
 */
class CatalogWriteIT extends IntegrationTestBase {

    private static final UUID COFFEE_CAT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String CASHIER = "00000000-0000-0000-0000-000000000005";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired ObjectStorageService storage;
    @Autowired ProductCommandService productCommands;
    @Autowired ProductManagementQueryService productQueries;
    @Autowired CategoryCommandService categoryCommands;
    @Autowired CategoryManagementQueryService categoryQueries;

    private CurrentActor admin;

    @BeforeEach
    void seedAdmin() {
        admin = seedActor(SUPER_ADMIN);
    }

    private CurrentActor seedActor(String roleId) {
        UUID userId = UUID.randomUUID();
        String email = "catalog-it-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "IT User", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?, null, ?)", UUID.randomUUID(), userId, UUID.fromString(roleId),
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    private String uploadPng(String prefix) {
        String key = prefix + "it-" + UUID.randomUUID() + ".png";
        byte[] payload = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
        storage.upload(key, "image/png", payload.length, new ByteArrayInputStream(payload));
        return key;
    }

    private CreateProductRequest productRequest(String code, String imageKey) {
        return new CreateProductRequest(code, COFFEE_CAT, "IT Product " + code, "desc",
                new BigDecimal("9.9900"), "USD", 4, new BigDecimal("0.070000"), true, true, imageKey,
                null, null);
    }

    /** Slice 4A: same payload with an explicit classification pair. */
    private CreateProductRequest productRequest(String code, String imageKey, String type, String mode) {
        return new CreateProductRequest(code, COFFEE_CAT, "IT Product " + code, "desc",
                new BigDecimal("9.9900"), "USD", 4, new BigDecimal("0.070000"), true, true, imageKey,
                type, mode);
    }

    // -------------------------------------------------------------- products

    @Test
    void createProductWithImagePersistsKeyAndReturnsSignedImageUrl() {
        String key = uploadPng("products/");
        ManagementProductResponse resp = productCommands.create(admin, productRequest("IMG_" + suffix(), key));

        assertEquals(key, resp.imageObjectKey());
        assertNotNull(resp.imageUrl());
        assertTrue(resp.imageUrl().contains("smart-coffee-storage"), resp.imageUrl());
        assertTrue(storage.exists(key));

        String stored = jdbc.queryForObject(
                "select image_object_key from catalog.products where product_id = ?", String.class, resp.productId());
        assertEquals(key, stored);
    }

    @Test
    void createProductWithoutImageHasNoUrl() {
        ManagementProductResponse resp = productCommands.create(admin, productRequest("NOIMG_" + suffix(), null));
        assertNull(resp.imageObjectKey());
        assertNull(resp.imageUrl());
    }

    @Test
    void managementListAndSearchReturnCreatedProduct() {
        String code = "LIST_" + suffix();
        productCommands.create(admin, productRequest(code, null));
        var page = productQueries.list(admin, code, null, "name", "asc", 0, 20);
        assertTrue(page.getContent().stream().anyMatch(p -> p.code().equals(code)));
    }

    @Test
    void replaceProductImageDeletesPreviousObjectAfterCommit() {
        String oldKey = uploadPng("products/");
        String newKey = uploadPng("products/");
        ManagementProductResponse created = productCommands.create(admin, productRequest("REPL_" + suffix(), oldKey));

        productCommands.update(admin, created.productId(),
                new UpdateProductRequest(created.code(), COFFEE_CAT, "IT Product", "desc",
                        new BigDecimal("9.9900"), "USD", 4, new BigDecimal("0.070000"), true, true, newKey,
                        created.version(), null, null));

        assertTrue(storage.exists(newKey));
        assertFalse(storage.exists(oldKey), "previous image must be reclaimed after the commit");
    }

    @Test
    void clearingProductImageDeletesObject() {
        String key = uploadPng("products/");
        ManagementProductResponse created = productCommands.create(admin, productRequest("CLR_" + suffix(), key));

        ManagementProductResponse updated = productCommands.update(admin, created.productId(),
                new UpdateProductRequest(created.code(), COFFEE_CAT, "IT Product", "desc",
                        new BigDecimal("9.9900"), "USD", 4, new BigDecimal("0.070000"), true, true, null,
                        created.version(), null, null));

        assertNull(updated.imageObjectKey());
        assertFalse(storage.exists(key));
    }

    @Test
    void productImageKeyMustBelongToProductsPrefix() {
        String categoryKey = uploadPng("categories/");
        StorageException ex = assertThrows(StorageException.class,
                () -> productCommands.create(admin, productRequest("WRONG_" + suffix(), categoryKey)));
        assertEquals("INVALID_OBJECT_KEY", ex.code());
    }

    @Test
    void duplicateProductCodeRejected() {
        String code = "DUP_" + suffix();
        productCommands.create(admin, productRequest(code, null));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> productCommands.create(admin, productRequest(code, null)));
        assertEquals("DUPLICATE_CODE", ex.code());
    }

    @Test
    void staleProductVersionRejected() {
        ManagementProductResponse created = productCommands.create(admin, productRequest("VER_" + suffix(), null));
        // First update bumps the version.
        productCommands.update(admin, created.productId(),
                new UpdateProductRequest(created.code(), COFFEE_CAT, "Renamed", "desc",
                        new BigDecimal("10.0000"), "USD", 4, new BigDecimal("0.070000"), true, true, null,
                        created.version(), null, null));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> productCommands.update(admin, created.productId(),
                        new UpdateProductRequest(created.code(), COFFEE_CAT, "Renamed again", "desc",
                                new BigDecimal("11.0000"), "USD", 4, new BigDecimal("0.070000"), true, true, null,
                                created.version(), null, null)));
        assertEquals("VERSION_CONFLICT", ex.code());
    }

    @Test
    void productWriteForbiddenWithoutPermission() {
        CurrentActor cashier = seedActor(CASHIER);
        assertThrows(ForbiddenException.class,
                () -> productCommands.create(cashier, productRequest("NOPE_" + suffix(), null)));
    }

    // -------------------------------------------------- classification (4A §1)

    /**
     * The V039 columns are {@code varchar(20) NOT NULL} with defaults, so an
     * explicit classification must land in the row exactly as the domain enum
     * name (uppercase), not as an ordinal or a quoted value.
     */
    @Test
    void createWithExplicitClassificationPersistsTheEnumNames() {
        ManagementProductResponse resp = productCommands.create(admin,
                productRequest("CLSC_" + suffix(), null, "beverage", "RECIPE_BASED"));

        assertEquals(ProductType.BEVERAGE, resp.productType());
        assertEquals(StockMode.RECIPE_BASED, resp.stockMode());
        assertEquals(0L, resp.version(), "create returns the server-issued version");

        String stored = jdbc.queryForObject(
                "select product_type || '/' || stock_mode from catalog.products where product_id = ?",
                String.class, resp.productId());
        assertEquals("BEVERAGE/RECIPE_BASED", stored, "unknown-case request value is normalised to the enum name");
    }

    /**
     * Legacy compatibility proof: a payload without the new fields must produce
     * the same row it produced before Slice 4A existed (V039 defaults), and must
     * not gain a recipe pointer or lose its active flag.
     */
    @Test
    void legacyCreateWithoutClassificationKeepsColumnDefaultsAndStaysSellable() {
        ManagementProductResponse resp = productCommands.create(admin, productRequest("CLSL_" + suffix(), null));

        assertEquals(ProductType.STANDARD, resp.productType());
        assertEquals(StockMode.MADE_TO_ORDER, resp.stockMode());

        var row = jdbc.queryForMap(
                "select product_type, stock_mode, active, active_recipe_version_id from catalog.products where product_id = ?",
                resp.productId());
        assertEquals("STANDARD", row.get("product_type"));
        assertEquals("MADE_TO_ORDER", row.get("stock_mode"));
        assertEquals(Boolean.TRUE, row.get("active"));
        assertNull(row.get("active_recipe_version_id"), "reclassification never forces a Recipe");
    }

    @Test
    void updateChangesBothClassificationHalvesAndBumpsTheReturnedVersion() {
        ManagementProductResponse created = productCommands.create(admin,
                productRequest("CLSU_" + suffix(), null, "STANDARD", "MADE_TO_ORDER"));

        ManagementProductResponse updated = productCommands.update(admin, created.productId(),
                new UpdateProductRequest(created.code(), COFFEE_CAT, "IT Product", "desc",
                        new BigDecimal("9.9900"), "USD", 4, new BigDecimal("0.070000"), true, true, null,
                        created.version(), "CAKE", "FINISHED_GOOD"));

        assertEquals(ProductType.CAKE, updated.productType());
        assertEquals(StockMode.FINISHED_GOOD, updated.stockMode());
        assertTrue(updated.version() > created.version(), "optimistic lock advances on a classification write");
        assertEquals("CAKE/FINISHED_GOOD", jdbc.queryForObject(
                "select product_type || '/' || stock_mode from catalog.products where product_id = ?",
                String.class, created.productId()));
    }

    /**
     * The dangerous asymmetry: an editor that does not carry the classification
     * must not silently rewrite it back to STANDARD / MADE_TO_ORDER, because
     * {@code null} is not the same thing as an explicit default.
     */
    @Test
    void updateOmittingClassificationPreservesStoredValuesAndStillAppliesOtherFields() {
        ManagementProductResponse created = productCommands.create(admin,
                productRequest("CLSP_" + suffix(), null, "BAKERY", "BATCH_PRODUCTION"));

        ManagementProductResponse updated = productCommands.update(admin, created.productId(),
                new UpdateProductRequest(created.code(), COFFEE_CAT, "Renamed only", "desc",
                        new BigDecimal("10.0000"), "USD", 6, new BigDecimal("0.070000"), true, true, null,
                        created.version(), null, null));

        assertEquals(ProductType.BAKERY, updated.productType(), "omitted halves must not reset the row");
        assertEquals(StockMode.BATCH_PRODUCTION, updated.stockMode());
        assertEquals("Renamed only", updated.name());
        assertEquals(0, new BigDecimal("10.0000").compareTo(updated.basePrice()));
    }

    @Test
    void unknownClassificationValueFailsAsValidationAndWritesNothing() {
        ManagementProductResponse created = productCommands.create(admin, productRequest("CLSB_" + suffix(), null));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> productCommands.update(admin, created.productId(),
                        new UpdateProductRequest(created.code(), COFFEE_CAT, "IT Product", "desc",
                                new BigDecimal("9.9900"), "USD", 4, new BigDecimal("0.070000"), true, true, null,
                                created.version(), "SOPHISTICATED", "MADE_TO_ORDER")));
        assertEquals("INVALID_PRODUCT_TYPE", ex.code());

        assertEquals("STANDARD", jdbc.queryForObject(
                "select product_type from catalog.products where product_id = ?", String.class, created.productId()),
                "a rejected write must leave the stored classification untouched");

        ValidationException mode = assertThrows(ValidationException.class,
                () -> productCommands.create(admin, productRequest("CLSB2_" + suffix(), null, "BEVERAGE", "FROZEN")));
        assertEquals("INVALID_STOCK_MODE", mode.code());
    }

    @Test
    void classificationWriteForbiddenWithoutPermissionLeavesRowUntouched() {
        ManagementProductResponse created = productCommands.create(admin, productRequest("CLSF_" + suffix(), null));
        CurrentActor cashier = seedActor(CASHIER);

        assertThrows(ForbiddenException.class,
                () -> productCommands.update(cashier, created.productId(),
                        new UpdateProductRequest(created.code(), COFFEE_CAT, "Hijacked", "desc",
                                new BigDecimal("9.9900"), "USD", 4, new BigDecimal("0.070000"), true, true, null,
                                created.version(), "BEVERAGE", "RECIPE_BASED")));

        assertEquals("STANDARD/MADE_TO_ORDER", jdbc.queryForObject(
                "select product_type || '/' || stock_mode from catalog.products where product_id = ?",
                String.class, created.productId()));
        assertEquals("IT Product " + created.code(), jdbc.queryForObject(
                "select product_name from catalog.products where product_id = ?", String.class, created.productId()));
    }

    /**
     * Reclassifying to RECIPE_BASED must not strand the product: no Recipe row is
     * created, the fallback pointer stays null, and the product keeps its
     * existing store listing — availability is computed, not side-effected.
     */
    @Test
    void reclassifyingToRecipeBasedCreatesNoRecipeAndDoesNotDeactivateTheProduct() {
        ManagementProductResponse created = productCommands.create(admin, productRequest("CLSR_" + suffix(), null));

        productCommands.update(admin, created.productId(),
                new UpdateProductRequest(created.code(), COFFEE_CAT, "IT Product", "desc",
                        new BigDecimal("9.9900"), "USD", 4, new BigDecimal("0.070000"), true, true, null,
                        created.version(), "BEVERAGE", "RECIPE_BASED"));

        var row = jdbc.queryForMap("select active, active_recipe_version_id from catalog.products where product_id = ?",
                created.productId());
        assertEquals(Boolean.TRUE, row.get("active"));
        assertNull(row.get("active_recipe_version_id"));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from recipe.recipe_versions where product_id = ?", Integer.class,
                created.productId()));
    }

    // ------------------------------------------------------------- categories

    @Test
    void createCategoryWithImageAndReadBack() {
        String key = uploadPng("categories/");
        String code = "CAT_" + suffix();
        ManagementCategoryResponse resp = categoryCommands.create(admin,
                new CreateCategoryRequest(null, code, "IT Category", "desc", 5, true, key));

        assertEquals(key, resp.imageObjectKey());
        assertTrue(resp.imageUrl().contains("smart-coffee-storage"));

        var fetched = categoryQueries.get(admin, resp.categoryId());
        assertEquals(code, fetched.code());
        assertEquals(key, fetched.imageObjectKey());
    }

    @Test
    void updateCategoryRenameBumpsVersion() {
        String code = "CATU_" + suffix();
        ManagementCategoryResponse created = categoryCommands.create(admin,
                new CreateCategoryRequest(null, code, "Before", null, 1, true, null));

        ManagementCategoryResponse updated = categoryCommands.update(admin, created.categoryId(),
                new UpdateCategoryRequest(null, code, "After", null, 2, true, null, created.version()));

        assertEquals("After", updated.name());
        assertEquals(2, updated.displayOrder());
        assertTrue(updated.version() > created.version());
        assertDoesNotThrow(() -> categoryQueries.get(admin, created.categoryId()));
    }

    @Test
    void categoryImageKeyMustBelongToCategoriesPrefix() {
        String productKey = uploadPng("products/");
        StorageException ex = assertThrows(StorageException.class, () -> categoryCommands.create(admin,
                new CreateCategoryRequest(null, "CATP_" + suffix(), "X", null, 1, true, productKey)));
        assertEquals("INVALID_OBJECT_KEY", ex.code());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
