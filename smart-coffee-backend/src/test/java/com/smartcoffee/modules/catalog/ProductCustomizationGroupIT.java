package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.ProductCustomizationGroupView;
import com.smartcoffee.modules.catalog.api.ProductCustomizationGroupsResponse;
import com.smartcoffee.modules.catalog.api.request.ConfigureProductCustomizationGroupRequest;
import com.smartcoffee.modules.catalog.application.ProductCustomizationGroupManagementService;
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
 * Slice 4A Round 1 — product customization-group management against the real
 * Testcontainers Postgres (V048 schema, no migration added by this slice).
 *
 * <p>Fixtures are created per test with unique codes so the master group rows
 * this suite needs (MULTIPLE/min 0, SINGLE/max 2, inactive) exist without
 * touching the V003 seeded MILK/SUGAR groups the POS and Customer catalog tests
 * depend on.
 *
 * <p>The suite is written to prove the <em>negative</em> half of the contract as
 * loudly as the positive half: the fields the attachment table cannot store are
 * refused with a stable code, and the row count / column count assertions prove
 * nothing was faked with shadow storage or a convenience migration.
 */
class ProductCustomizationGroupIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String CASHIER = "00000000-0000-0000-0000-000000000005";

    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired ProductCustomizationGroupManagementService service;

    private CurrentActor admin;
    private UUID product;
    /** SINGLE, min 1 / max 1, active — the happy-path group. */
    private UUID groupSingle;
    /** MULTIPLE, min 0 / max null, active — cannot carry required=true. */
    private UUID groupOptional;
    /** SINGLE but max 2 — a master row the domain must refuse to honour. */
    private UUID groupBadSingle;
    /** active=false — cannot be attached at all. */
    private UUID groupInactive;
    private UUID optionA;
    private UUID optionB;
    /** inactive option of groupSingle — never part of the effective allow-list. */
    private UUID optionInactive;

    @BeforeEach
    void setUp() {
        admin = seedActor(SUPER_ADMIN);
        product = newProduct();
        groupSingle = newGroup("SINGLE", 1, 1, true);
        groupOptional = newGroup("MULTIPLE", 0, null, true);
        groupBadSingle = newGroup("SINGLE", 1, 2, true);
        groupInactive = newGroup("SINGLE", 1, 1, false);
        optionA = newOption(groupSingle, true);
        optionB = newOption(groupSingle, true);
        optionInactive = newOption(groupSingle, false);
        newOption(groupOptional, true);
    }

    // -------------------------------------------------------------------- read

    @Test
    void readReportsMasterLimitsActiveOptionsAndNoDefault() {
        // The read orders options by (display_order, name). Fixture names carry random
        // codes, so the order is pinned through display_order instead of by name.
        jdbc.update("update catalog.customization_options set display_order=20 where option_id=?", optionB);
        configure(groupSingle, req(true, 5, productVersion()));

        ProductCustomizationGroupsResponse resp = service.list(admin, product);

        assertEquals(product, resp.productId());
        assertEquals(1, resp.groups().size());
        ProductCustomizationGroupView view = resp.groups().get(0);
        assertEquals(groupSingle, view.groupId());
        assertEquals("SINGLE", view.selectionType());
        // Inherited from the master group, NOT stored per product.
        assertEquals(1, view.minimumSelections());
        assertEquals(Integer.valueOf(1), view.maximumSelections());
        // Per-product writable half.
        assertTrue(view.required());
        assertEquals(5, view.displayOrder());
        // Round 1 has no storage for a per-product default.
        assertNull(view.defaultOptionId(), "no default option column exists — the read must not invent one");
        assertFalse(view.allowedOptions().stream().anyMatch(o -> o.optionId().equals(optionInactive)),
                "inactive options are not part of the effective allow-list");
        assertEquals(List.of(optionA, optionB), view.allowedOptions().stream().map(o -> o.optionId()).toList(),
                "the effective allow-list is every active option, in master display order");
        assertTrue(view.allowedOptions().stream().allMatch(o -> !o.defaultOption()));
    }

    @Test
    void readDeclaresWhichPerProductFieldsRoundTwoCanPersist() {
        var caps = service.list(admin, product).capabilities();

        assertTrue(caps.supportsRequiredOverride());
        assertTrue(caps.supportsDisplayOrderOverride());
        assertTrue(caps.supportsMinimumSelectionsOverride(), "V049 added the per-product minimum column");
        assertTrue(caps.supportsMaximumSelectionsOverride(), "V049 added the per-product maximum column");
        assertTrue(caps.supportsDefaultOptionOverride(), "V049 added the per-product default column");
        assertTrue(caps.supportsAllowedOptionSubset(), "V049 added the product/option allow-list table");
        assertEquals("RELATIONSHIP", caps.relationshipVersionSource());
    }

    @Test
    void relationshipVersionIsTheIndependentAttachmentTokenNotTheProductVersion() {
        long beforeVersion = productVersion();
        configure(groupSingle, req(true, 1, beforeVersion));

        ProductCustomizationGroupsResponse resp = service.list(admin, product);
        assertEquals(beforeVersion + 1, resp.productVersion(), "attach advanced the product aggregate");
        // A brand-new attachment row starts at its own version 0 — independent of the
        // product aggregate, which is exactly why RELATIONSHIP is now the lock source.
        assertEquals(0L, resp.groups().get(0).relationshipVersion(),
                "the relationship carries its own optimistic-lock token from V049 on");

        // A second (re)configure advances the attachment's own version and no longer
        // needs the product version to have moved for the guard to be meaningful.
        long rel = resp.groups().get(0).relationshipVersion();
        ProductCustomizationGroupsResponse after = configure(groupSingle,
                new ConfigureProductCustomizationGroupRequest(true, 1, null, null, null, null, null,
                        productVersion(), rel));
        assertTrue(after.groups().get(0).relationshipVersion() > rel,
                "reconfiguring the attachment advances its relationship version");
    }

    @Test
    void readOfUnknownProductIsNotFound() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.list(admin, UUID.randomUUID()));
        assertEquals("PRODUCT_NOT_FOUND", ex.code());
    }

    @Test
    void productWithoutAttachmentsReadsAsEmptyListNotMissing() {
        ProductCustomizationGroupsResponse resp = service.list(admin, product);
        assertEquals(List.of(), resp.groups());
        assertNotNull(resp.capabilities(), "capabilities travel with every read");
    }

    @Test
    void readExposesOptionVersionAndInventoryEffectNumbersWithoutFloatingLoss() {
        // Sugar-style MULTIPLY 0.50 effect: the value must arrive as an exact decimal.
        // The ingredient is seeded because no migration seeds inventory.ingredients —
        // ck_option_effect_shape requires a source ingredient for MULTIPLY.
        UUID ingredient = seedIngredient();
        configure(groupSingle, req(true, 1, productVersion()));
        jdbc.update("update catalog.customization_options set inventory_effect_type='MULTIPLY', "
                + "source_ingredient_id=?, effect_multiplier=0.5000, additional_price=1.2500 where option_id=?",
                ingredient, optionA);

        var option = service.list(admin, product).groups().stream().flatMap(g -> g.allowedOptions().stream())
                .filter(o -> o.optionId().equals(optionA)).findFirst().orElseThrow();

        assertEquals(0, new BigDecimal("1.2500").compareTo(option.priceDelta()));
        assertEquals(0, new BigDecimal("0.5000").compareTo(option.inventoryEffect().effectMultiplier()));
        assertEquals("MULTIPLY", option.inventoryEffect().type().name());
        assertEquals(ingredient, option.inventoryEffect().sourceIngredientId());
        assertNull(option.numericValue(), "no generic numeric option column exists in Round 1");
        assertTrue(option.version() >= 0, "option version is carried for the option-effect editor");
    }

    // ------------------------------------------------------------------- write

    @Test
    void attachPersistsBothWritableColumnsAndAdvancesTheProductVersion() {
        long before = productVersion();
        ProductCustomizationGroupsResponse resp = configure(groupSingle, req(true, 7, before));

        assertEquals(before + 1, resp.productVersion(), "the lock token must move or the guard is fiction");
        assertEquals(before + 1, productVersion());

        var row = jdbc.queryForMap("select required, display_order from catalog.product_customization_groups "
                + "where product_id=? and group_id=?", product, groupSingle);
        assertEquals(Boolean.TRUE, row.get("required"));
        assertEquals(7, ((Number) row.get("display_order")).intValue());
    }

    @Test
    void omittedFlagsFallBackToTheColumnDefaultsExactlyLikeASqlSeed() {
        configure(groupSingle, new ConfigureProductCustomizationGroupRequest(null, null, null, null, null, null, null,
                productVersion(), null));

        var row = jdbc.queryForMap("select required, display_order from catalog.product_customization_groups "
                + "where product_id=? and group_id=?", product, groupSingle);
        assertEquals(Boolean.FALSE, row.get("required"));
        assertEquals(0, ((Number) row.get("display_order")).intValue());
    }

    @Test
    void secondAttachUpdatesInPlaceAndNeverCreatesADuplicateRelationship() {
        configure(groupSingle, req(false, 1, productVersion()));
        configure(groupSingle, req(true, 9, productVersion()));

        Integer rows = jdbc.queryForObject("select count(*) from catalog.product_customization_groups "
                + "where product_id=? and group_id=?", Integer.class, product, groupSingle);
        assertEquals(1, rows.intValue(), "the composite key is the duplicate guard");
        assertEquals(1, service.list(admin, product).groups().size(), "still exactly one attachment");
        assertEquals(9, service.list(admin, product).groups().get(0).displayOrder());
    }

    @Test
    void staleExpectedProductVersionIsConflictAndWritesNothing() {
        configure(groupSingle, req(true, 3, productVersion()));
        long now = productVersion();

        ConflictException ex = assertThrows(ConflictException.class,
                () -> configure(groupOptional, req(true, 4, now - 1)));
        assertEquals("PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT", ex.code());
        assertEquals(1, attachmentCount(), "a rejected write must not leave a second attachment");
        assertEquals(now, productVersion(), "a rejected write must not advance the lock token");
    }

    @Test
    void staleRelationshipVersionIsConflictEvenWhenTheProductVersionMatches() {
        // Attach the group (relationship version 0), then a client that read a newer
        // relationship token than the row actually carries is stale exactly on the
        // RELATIONSHIP token — the product version may be current and it is still a 409.
        configure(groupSingle, req(true, 1, productVersion()));
        long currentRelationship = service.list(admin, product).groups().get(0).relationshipVersion();

        ConflictException ex = assertThrows(ConflictException.class,
                () -> configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 2, null, null,
                        null, null, null, productVersion(), currentRelationship + 5)));
        assertEquals("PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT", ex.code());
        // The rejected write changed nothing.
        assertEquals(1, service.list(admin, product).groups().get(0).displayOrder());
    }

    @Test
    void unknownGroupIsNotFoundAndUnknownProductIsNotFound() {
        ResourceNotFoundException group = assertThrows(ResourceNotFoundException.class,
                () -> configure(UUID.randomUUID(), req(true, 1, productVersion())));
        assertEquals("CUSTOMIZATION_GROUP_NOT_FOUND", group.code());

        ResourceNotFoundException prod = assertThrows(ResourceNotFoundException.class,
                () -> service.configure(admin, UUID.randomUUID(), groupSingle, req(true, 1, 0L)));
        assertEquals("PRODUCT_NOT_FOUND", prod.code());
    }

    @Test
    void inactiveGroupCannotBeAttached() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> configure(groupInactive, req(true, 1, productVersion())));
        assertEquals("CUSTOMIZATION_GROUP_INACTIVE", ex.code());
        assertEquals(0, attachmentCount());
    }

    @Test
    void requiredFlagOnAGroupThatAllowsZeroSelectionsIsRejected() {
        // groupOptional is MULTIPLE with minimum_selections = 0: marking it
        // required would let the editor demand a choice the master group does not.
        ValidationException ex = assertThrows(ValidationException.class,
                () -> configure(groupOptional, req(true, 1, productVersion())));
        assertEquals("INVALID_SELECTION_LIMITS", ex.code());

        // The same group attaches fine when it is not required.
        assertEquals(1, configure(groupOptional, req(false, 1, productVersion())).groups().size());
    }

    @Test
    void singleGroupDeclaringMaxTwoIsRefused() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> configure(groupBadSingle, req(true, 1, productVersion())));
        assertEquals("INVALID_SELECTION_LIMITS", ex.code());
        assertTrue(ex.getMessage().contains("SINGLE"), ex.getMessage());
    }

    // ---------------------------------------------- per-product override writes

    @Test
    void perProductSelectionLimitsOverridePersistsAndReadsBack() {
        // groupOptional is MULTIPLE min 0 / max null: a per-product 1..3 override is
        // stored on the attachment and reported as the effective limit.
        configure(groupOptional, new ConfigureProductCustomizationGroupRequest(false, 1, 1, 3, null, null, null,
                productVersion(), null));

        var view = service.list(admin, product).groups().get(0);
        assertEquals(1, view.minimumSelections(), "the override is now authoritative, not inherited");
        assertEquals(Integer.valueOf(3), view.maximumSelections());

        // A SINGLE group still may not be widened past 1 — the shape rule moved from
        // "override unsupported" to "override invalid", same stable code family.
        configure(groupSingle, req(true, 1, productVersion()));
        ValidationException tooWide = assertThrows(ValidationException.class,
                () -> configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 1, null, 3, null,
                        null, null, productVersion(), null)));
        assertEquals("INVALID_SELECTION_LIMITS", tooWide.code());
    }

    @Test
    void overridingMinimumAboveMaximumIsRejected() {
        configure(groupOptional, req(false, 1, productVersion()));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> configure(groupOptional, new ConfigureProductCustomizationGroupRequest(false, 1, 5, 2, null,
                        null, null, productVersion(), null)));
        assertEquals("INVALID_SELECTION_LIMITS", ex.code());
    }

    @Test
    void perProductDefaultPersistsWhenAllowedAndIsRefusedOtherwise() {
        // Attaching with a default among the (all-active) allowed set stores it.
        configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 1, null, null, null, null,
                optionA, productVersion(), null));

        var view = service.list(admin, product).groups().get(0);
        assertEquals(optionA, view.defaultOptionId());
        assertEquals(1, view.allowedOptions().stream().filter(o -> o.defaultOption()).count(),
                "exactly one option is flagged as the default");
        assertTrue(view.allowedOptions().stream().filter(o -> o.optionId().equals(optionA))
                .findFirst().orElseThrow().defaultOption());

        // A default outside the allowed subset is refused with the stable code.
        configure(groupOptional, new ConfigureProductCustomizationGroupRequest(false, 1, null, null,
                List.of(newOption(groupOptional, true)), null, null, productVersion(), null));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.configure(admin, product, groupOptional,
                        new ConfigureProductCustomizationGroupRequest(false, 1, null, null, null, null,
                                optionA, productVersion(), null)));
        assertEquals("DEFAULT_OPTION_NOT_ALLOWED", ex.code());
    }

    @Test
    void allowedSubsetPersistsAndInactiveForeignOrUnknownOptionsAreRefused() {
        // Narrowing the subset to a single active option is a real, persisted change.
        configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 1, null, null,
                List.of(optionA), null, null, productVersion(), null));
        var view = service.list(admin, product).groups().get(0);
        assertEquals(List.of(optionA), view.allowedOptions().stream().map(o -> o.optionId()).toList(),
                "only the allowed option is part of the effective set the reads will serve");

        ValidationException inactive = assertThrows(ValidationException.class,
                () -> configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 2, null, null,
                        List.of(optionA, optionInactive), null, null, productVersion(), null)));
        assertEquals("OPTION_NOT_ALLOWED", inactive.code());

        UUID foreign = newOption(groupOptional, true);
        ValidationException foreignErr = assertThrows(ValidationException.class,
                () -> configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 2, null, null,
                        List.of(optionA, foreign), null, null, productVersion(), null)));
        assertEquals("OPTION_NOT_ALLOWED", foreignErr.code());

        ValidationException unknown = assertThrows(ValidationException.class,
                () -> configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 2, null, null,
                        List.of(UUID.randomUUID()), null, null, productVersion(), null)));
        assertEquals("OPTION_NOT_FOUND", unknown.code());

        ValidationException dup = assertThrows(ValidationException.class,
                () -> configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 2, null, null, null,
                        List.of(new ConfigureProductCustomizationGroupRequest.OptionOverride(optionA, true, null, null),
                                new ConfigureProductCustomizationGroupRequest.OptionOverride(optionA, true, null, null)),
                        null, productVersion(), null)));
        assertEquals("DUPLICATE_OPTION_SELECTION", dup.code());
    }

    @Test
    void perProductPriceOverrideFlowsIntoTheEffectiveRead() {
        configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 1, null, null, null,
                List.of(new ConfigureProductCustomizationGroupRequest.OptionOverride(optionA, true,
                        new BigDecimal("3.50"), null)), null, productVersion(), null));

        var optionAView = service.list(admin, product).groups().get(0).allowedOptions().stream()
                .filter(o -> o.optionId().equals(optionA)).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3.50").compareTo(optionAView.priceDelta()),
                "the per-product price override replaces the master add-on price");
    }

    @Test
    void aRejectedOverrideWritesNothingAndBurnsNoVersion() {
        long version = productVersion();
        ValidationException ex = assertThrows(ValidationException.class,
                () -> configure(groupSingle, new ConfigureProductCustomizationGroupRequest(true, 6, 1, 1,
                        List.of(optionA), null, optionB, version, null)));
        // defaultOptionId=optionB is outside the requested allowed subset {optionA}.
        assertEquals("DEFAULT_OPTION_NOT_ALLOWED", ex.code());
        assertEquals(0, attachmentCount(), "a rejected attach writes nothing");
        assertEquals(version, productVersion(), "a rejected attach does not burn a version");
    }


    // ------------------------------------------------------------------ detach

    @Test
    void detachRemovesOnlyTheRelationshipAndAdvancesTheProductVersion() {
        configure(groupSingle, req(true, 1, productVersion()));
        long beforeDetach = productVersion();

        ProductCustomizationGroupsResponse after = service.detach(admin, product, groupSingle, beforeDetach);

        assertEquals(beforeDetach + 1, after.productVersion());
        assertEquals(0, attachmentCount());
        assertEquals(List.of(), service.list(admin, product).groups());

        // Masters survive the detach.
        assertEquals(1, groupRowCount(groupSingle));
        assertEquals(3, optionCountFor(groupSingle));
    }

    @Test
    void repeatedDetachOfTheSameGroupIsAStableNotFound() {
        configure(groupSingle, req(true, 1, productVersion()));
        service.detach(admin, product, groupSingle, productVersion());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.detach(admin, product, groupSingle, productVersion()));
        assertEquals("PRODUCT_CUSTOMIZATION_GROUP_NOT_FOUND", ex.code());
    }

    @Test
    void detachWithStaleVersionIsConflictAndKeepsTheAttachment() {
        configure(groupSingle, req(true, 1, productVersion()));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.detach(admin, product, groupSingle, productVersion() - 1));
        assertEquals("PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT", ex.code());
        assertEquals(1, attachmentCount());
    }

    @Test
    void detachTouchesNoCartOrderInventoryOrOtherProductAttachment() {
        UUID otherProduct = newProduct();
        configure(groupSingle, req(true, 1, productVersion()));
        service.configure(admin, otherProduct, groupSingle, req(true, 2, versionOf(otherProduct)));

        long carts = cnt("select count(*) from cart.carts");
        long cartItems = cnt("select count(*) from cart.cart_items");
        long orders = cnt("select count(*) from ordering.orders");
        long orderItems = cnt("select count(*) from ordering.order_items");
        long balances = cnt("select count(*) from inventory.inventory_balances");
        long ledger = cnt("select count(*) from inventory.stock_transactions");
        long groups = cnt("select count(*) from catalog.customization_groups");
        long options = cnt("select count(*) from catalog.customization_options");

        service.detach(admin, product, groupSingle, productVersion());

        assertEquals(carts, cnt("select count(*) from cart.carts"));
        assertEquals(cartItems, cnt("select count(*) from cart.cart_items"));
        assertEquals(orders, cnt("select count(*) from ordering.orders"));
        assertEquals(orderItems, cnt("select count(*) from ordering.order_items"));
        assertEquals(balances, cnt("select count(*) from inventory.inventory_balances"));
        assertEquals(ledger, cnt("select count(*) from inventory.stock_transactions"));
        assertEquals(groups, cnt("select count(*) from catalog.customization_groups"), "masters are never deleted");
        assertEquals(options, cnt("select count(*) from catalog.customization_options"), "options are never deleted");
        assertEquals(1, attachmentsFor(otherProduct), "only the addressed product's attachment went away");
    }

    // ------------------------------------------------------------- permissions

    @Test
    void readAndWriteRequireProductManageAndRefuseWithoutIt() {
        CurrentActor cashier = seedActor(CASHIER);

        assertThrows(ForbiddenException.class, () -> service.list(cashier, product));
        assertThrows(ForbiddenException.class, () -> service.configure(cashier, product, groupSingle,
                req(true, 1, productVersion())));
        assertThrows(ForbiddenException.class,
                () -> service.detach(cashier, product, groupSingle, productVersion()));
        assertEquals(0, attachmentCount(), "a refused write leaves no row");
        assertEquals(0L, productVersion(), "a refused write does not burn a version");
    }

    // ------------------------------------------------------------------- audit

    @Test
    void attachUpdateAndDetachEachWriteOneAuditedSnapshotWithNoSqlDetails() {
        configure(groupSingle, req(true, 1, productVersion()));
        configure(groupSingle, req(false, 2, productVersion()));
        service.detach(admin, product, groupSingle, productVersion());

        // Counts are scoped to this product's aggregate id: the container is shared
        // across the whole class, so a global count would see other tests' rows.
        assertEquals(1, auditCount("CATALOG_PRODUCT_GROUP_ATTACH"));
        assertEquals(1, auditCount("CATALOG_PRODUCT_GROUP_UPDATE"));
        assertEquals(1, auditCount("CATALOG_PRODUCT_GROUP_DETACH"));

        JsonNode attach = auditJson("new_values", "CATALOG_PRODUCT_GROUP_ATTACH");
        assertEquals(groupSingle.toString(), attach.path("groupId").asText(), attach.toString());
        assertTrue(attach.path("required").asBoolean());
        assertEquals(1, attach.path("displayOrder").asInt());
        assertEquals("SINGLE", attach.path("selectionType").asText());
        assertTrue(attach.path("defaultOptionId").isNull(), "the audited snapshot must not invent a default");
        assertEquals(2, attach.path("allowedOptions").size(), "the effective allow-list is audited as resolved");

        // The 'before' half of the UPDATE proves the snapshot was taken before the
        // mutation; taken afterwards it would repeat the new values and be useless.
        assertEquals(1, auditJson("old_values", "CATALOG_PRODUCT_GROUP_UPDATE").path("displayOrder").asInt());
        assertEquals(2, auditJson("new_values", "CATALOG_PRODUCT_GROUP_UPDATE").path("displayOrder").asInt());
        assertEquals(2, auditJson("old_values", "CATALOG_PRODUCT_GROUP_DETACH").path("displayOrder").asInt());
        assertTrue(auditJsonNull("new_values", "CATALOG_PRODUCT_GROUP_DETACH"),
                "a detach has no after-state");

        String raw = jdbc.queryForObject("select (coalesce(old_values::text,'') || coalesce(new_values::text,'') "
                + "|| coalesce(reason,'')) from audit.audit_logs where action='CATALOG_PRODUCT_GROUP_ATTACH' "
                + "and aggregate_id=? limit 1", String.class, product);
        for (String leak : List.of("insert into", "product_customization_groups", "exception", "at com.",
                "psql", "constraint")) {
            assertFalse(raw.toLowerCase().contains(leak), "audit leaked '" + leak + "': " + raw);
        }
    }

    // --------------------------------------------------------- schema honesty

    /**
     * The schema-honesty assertion, updated for Round 2. Round 1 added no
     * migration and so the attachment row stayed at its four V003 columns; V049
     * is the version that lands the per-product override storage the richer
     * management contract promises, so the head is now V049, the attachment row
     * carries the override columns, and the product/option allow-list table
     * exists. The point is unchanged: every writable field the API exposes must
     * be backed by a real column, never fabricated.
     */
    @Test
    void v049OverrideColumnsPersistWithTheSchemaHeadAdvancedToV050() {
        String head = jdbc.queryForObject(
                "select max(version) from flyway_schema_history where success and version is not null", String.class);
        // V049 remains applied (its columns are asserted below); the schema head is now
        // V053 (SUPER_ADMIN RBAC hardening was V052; V053 is Phase B2 promotion
        // checkout reservation).
        assertEquals("053", head, "V053 is the migration head after Phase B2");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '049' and success", Integer.class).intValue(),
                "V049 applied exactly once");

        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema='catalog' "
                        + "and table_name='product_customization_groups'", String.class);
        for (String required : List.of("product_id", "group_id", "required", "display_order", "version",
                "minimum_selections", "maximum_selections", "default_option_id", "active", "created_at", "updated_at",
                "created_by", "updated_by")) {
            assertTrue(columns.contains(required), "attachment row is missing the V049 column '" + required + "'");
        }

        Integer overrideTable = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema='catalog' "
                        + "and table_name='product_customization_options'", Integer.class);
        assertEquals(1, overrideTable.intValue(), "the V049 allow-list table exists");
    }

    // ------------------------------------------------------------------ helpers

    private ProductCustomizationGroupsResponse configure(UUID groupId, ConfigureProductCustomizationGroupRequest req) {
        return service.configure(admin, product, groupId, req);
    }

    private ConfigureProductCustomizationGroupRequest req(boolean required, int displayOrder, Long expectedVersion) {
        return new ConfigureProductCustomizationGroupRequest(required, displayOrder, null, null, null, null, null,
                expectedVersion, null);
    }

    private long productVersion() {
        return versionOf(product);
    }

    private long versionOf(UUID productId) {
        Long v = jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class, productId);
        return v == null ? -1L : v;
    }

    private int attachmentCount() {
        return attachmentsFor(product);
    }

    private int attachmentsFor(UUID productId) {
        Integer n = jdbc.queryForObject("select count(*) from catalog.product_customization_groups where product_id=?",
                Integer.class, productId);
        return n == null ? 0 : n;
    }

    private int groupRowCount(UUID groupId) {
        Integer n = jdbc.queryForObject("select count(*) from catalog.customization_groups where group_id=?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    private int optionCountFor(UUID groupId) {
        Integer n = jdbc.queryForObject("select count(*) from catalog.customization_options where group_id=?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    private long cnt(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    private int auditCount(String action) {
        Integer n = jdbc.queryForObject("select count(*) from audit.audit_logs where action=? "
                + "and aggregate_type='PRODUCT' and aggregate_id=?", Integer.class, action, product);
        return n == null ? 0 : n;
    }

    /**
     * Reads one audited jsonb column of this product's most recent row for the action.
     * Parsed rather than substring-matched: Postgres normalises jsonb text output with
     * spaces after every colon, so a raw-string assertion would be brittle.
     */
    private JsonNode auditJson(String column, String action) {
        String text = jdbc.queryForObject("select " + column + "::text from audit.audit_logs where action=? "
                + "and aggregate_type='PRODUCT' and aggregate_id=? order by occurred_at desc limit 1",
                String.class, action, product);
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable audit " + column + ": " + text, e);
        }
    }

    private boolean auditJsonNull(String column, String action) {
        Integer n = jdbc.queryForObject("select count(*) from audit.audit_logs where action=? "
                + "and aggregate_type='PRODUCT' and aggregate_id=? and " + column + " is null", Integer.class,
                action, product);
        return n != null && n == 1;
    }

    private UUID seedIngredient() {
        UUID uom = jdbc.queryForObject("select unit_id from inventory.units_of_measure limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, "
                + "base_unit_id, standard_cost, track_inventory, track_batches, active, created_at, updated_at, "
                + "version) values (?,?,?,?,0,false,false,true,now(),now(),0)", id,
                "PCGI-" + UUID.randomUUID().toString().substring(0, 8), "PCG Ingredient", uom);
        return id;
    }

    private UUID newProduct() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,'PCG Product',9.00,'USD',4,0.10,true,true,now(),now(),0)", id,
                "PCG-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.fromString("20000000-0000-0000-0000-000000000001"));
        return id;
    }

    private UUID newGroup(String selectionType, int min, Integer max, boolean active) {
        UUID id = UUID.randomUUID();
        String code = "PCGG-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.customization_groups (group_id, group_code, group_name, selection_type, "
                + "minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,?,?,?,now(),now(),0)", id, code, "Group " + code, selectionType, min, max,
                10, active);
        return id;
    }

    private UUID newOption(UUID groupId, boolean active) {
        UUID id = UUID.randomUUID();
        String code = "PCGO-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.customization_options (option_id, group_id, option_code, option_name, "
                + "additional_price, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,0.0000,10,?,now(),now(),0)", id, groupId, code, "Option " + code, active);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID userId = UUID.randomUUID();
        String email = "pcg-it-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "PCG Actor", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,null,?)", UUID.randomUUID(), userId, UUID.fromString(roleId),
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    @SuppressWarnings("unused")
    private static final List<String> UNUSED = List.of();
}
