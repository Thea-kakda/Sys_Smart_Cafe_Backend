package com.smartcoffee.modules.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.CustomizationGroupMasterResponse;
import com.smartcoffee.modules.catalog.api.CustomizationOptionMasterResponse;
import com.smartcoffee.modules.catalog.api.request.CreateCustomizationGroupRequest;
import com.smartcoffee.modules.catalog.api.request.CreateCustomizationOptionRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCustomizationGroupRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCustomizationOptionRequest;
import com.smartcoffee.modules.catalog.application.CustomizationMasterManagementService;
import com.smartcoffee.modules.catalog.domain.SelectionType;
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
 * Slice 4A Round 2 §7 — master customization group/option management against
 * the real Testcontainers Postgres. Fixtures are created through the service
 * itself with unique codes so nothing here can disturb the V003-seeded
 * MILK/SUGAR masters the POS/Customer catalog suites depend on.
 *
 * <p>The suite pins the four things the §7 audit demanded of any new master
 * surface: permissions, audit, optimistic locking and the cross-aggregate
 * invariant that a master edit can never strand a product's effective limits.
 */
class CustomizationMasterManagementIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String CUSTOMER = "00000000-0000-0000-0000-000000000007";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired CustomizationMasterManagementService service;

    private CurrentActor admin;

    @BeforeEach
    void setUp() {
        admin = seedActor(SUPER_ADMIN);
    }

    // ------------------------------------------------------------------ groups

    @Test
    void createGroupPersistsTheMasterRowAndAuditsIt() {
        CustomizationGroupMasterResponse resp = createGroup(SelectionType.MULTIPLE, 0, null, 5);

        var row = jdbc.queryForMap("select group_code, group_name, selection_type, minimum_selections, "
                + "maximum_selections, display_order, active, version from catalog.customization_groups "
                + "where group_id=?", resp.groupId());
        assertEquals(resp.code(), row.get("group_code"));
        assertEquals("MULTIPLE", row.get("selection_type"));
        assertEquals(0, ((Number) row.get("minimum_selections")).intValue());
        assertEquals(5, ((Number) row.get("display_order")).intValue());
        assertEquals(Boolean.TRUE, row.get("active"));
        assertEquals(0L, ((Number) row.get("version")).longValue());
        assertEquals(1, auditRows("CATALOG_CUSTOMIZATION_GROUP_CREATE", resp.groupId()));
    }

    @Test
    void duplicateGroupCodeIsAStableConflict() {
        CustomizationGroupMasterResponse first = createGroup(SelectionType.SINGLE, 0, 1, 1);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> createGroupRaw(first.code(), "Clash", SelectionType.SINGLE, 0, 1, 2));
        assertEquals("DUPLICATE_CUSTOMIZATION_GROUP", ex.code());
    }

    @Test
    void singleGroupMustCapMaximumAtOne() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> createGroup(SelectionType.SINGLE, 1, 2, 1));
        assertEquals("INVALID_SELECTION_LIMITS", ex.code());
    }

    @Test
    void updateWithStaleVersionIsConflictAndChangesNothing() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 0, null, 1);

        ConflictException ex = assertThrows(ConflictException.class, () -> service.updateGroup(admin, g.groupId(),
                new UpdateCustomizationGroupRequest(g.version() + 5, "Renamed", 0, null, 9)));
        assertEquals("CUSTOMIZATION_GROUP_VERSION_CONFLICT", ex.code());
        assertEquals(g.name(), jdbc.queryForObject("select group_name from catalog.customization_groups "
                + "where group_id=?", String.class, g.groupId()));
    }

    @Test
    void updateAdvancesTheVersionAndPersistsTheNewFields() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 0, 5, 1);

        CustomizationGroupMasterResponse after = service.updateGroup(admin, g.groupId(),
                new UpdateCustomizationGroupRequest(g.version(), "Late Special", 2, 4, 8));

        assertEquals(g.version() + 1, after.version());
        assertEquals("Late Special", after.name());
        assertEquals(2, after.minimumSelections());
        assertEquals(Integer.valueOf(4), after.maximumSelections());
        assertEquals(1, auditRows("CATALOG_CUSTOMIZATION_GROUP_UPDATE", g.groupId()));
    }

    @Test
    void narrowingMasterMaximumIsRefusedWhileAProductOverrideInheritsPastIt() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 0, null, 1);
        UUID product = newProduct();
        attach(product, g.groupId(), false, 5, null); // per-product min override 5, max inherits

        ValidationException ex = assertThrows(ValidationException.class, () -> service.updateGroup(admin, g.groupId(),
                new UpdateCustomizationGroupRequest(g.version(), g.name(), 0, 3, 1)));
        assertEquals("MASTER_LIMITS_BREAK_PRODUCT_OVERRIDES", ex.code());

        // Fixing the product side first unblocks the master edit.
        jdbc.update("update catalog.product_customization_groups set minimum_selections=2 where product_id=? "
                + "and group_id=?", product, g.groupId());
        CustomizationGroupMasterResponse after = service.updateGroup(admin, g.groupId(),
                new UpdateCustomizationGroupRequest(g.version(), g.name(), 0, 3, 1));
        assertEquals(Integer.valueOf(3), after.maximumSelections());
    }

    @Test
    void loweringMasterMinimumIsRefusedWhileARequiredAttachmentInheritsIt() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 1, 5, 1);
        UUID product = newProduct();
        attach(product, g.groupId(), true, null, null); // required, min inherits master 1

        ValidationException ex = assertThrows(ValidationException.class, () -> service.updateGroup(admin, g.groupId(),
                new UpdateCustomizationGroupRequest(g.version(), g.name(), 0, 5, 1)));
        assertEquals("MASTER_LIMITS_BREAK_PRODUCT_OVERRIDES", ex.code());

        jdbc.update("update catalog.product_customization_groups set minimum_selections=1 where product_id=? "
                + "and group_id=?", product, g.groupId());
        assertNotNull(service.updateGroup(admin, g.groupId(),
                new UpdateCustomizationGroupRequest(g.version(), g.name(), 0, 5, 1)));
    }

    // ----------------------------------------------------------------- options

    @Test
    void optionLifecycleCreateUpdateDeactivateAndReActivate() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 0, null, 1);

        CustomizationOptionMasterResponse o = service.createOption(admin, g.groupId(),
                new CreateCustomizationOptionRequest("OAT-" + suffix(), "Oat Milk", new BigDecimal("0.50"), 3));
        assertEquals(0, new BigDecimal("0.50").compareTo(o.additionalPrice()));
        assertTrue(o.active());
        assertEquals("NONE", o.inventoryEffect().type().name(), "a fresh option carries no effect");

        CustomizationOptionMasterResponse updated = service.updateOption(admin, o.optionId(),
                new UpdateCustomizationOptionRequest(o.version(), "Oat Milk Large", new BigDecimal("0.75"), 4));
        assertEquals(o.version() + 1, updated.version());
        assertEquals("Oat Milk Large", updated.name());

        CustomizationOptionMasterResponse off = service.setOptionActive(admin, o.optionId(), false);
        assertFalse(off.active());
        assertTrue(service.setOptionActive(admin, o.optionId(), true).active());
        assertEquals(1, auditRows("CATALOG_CUSTOMIZATION_OPTION_CREATE", g.groupId()));
        assertEquals(1, auditRows("CATALOG_CUSTOMIZATION_OPTION_DEACTIVATE", g.groupId()));
    }

    @Test
    void duplicateOptionCodeWithinTheGroupIsAStableConflict() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 0, null, 1);
        String code = "DUP-" + suffix();
        service.createOption(admin, g.groupId(), new CreateCustomizationOptionRequest(code, "A", BigDecimal.ZERO, 1));

        ConflictException ex = assertThrows(ConflictException.class, () -> service.createOption(admin, g.groupId(),
                new CreateCustomizationOptionRequest(code.toLowerCase(), "B", BigDecimal.ZERO, 2)));
        assertEquals("DUPLICATE_CUSTOMIZATION_OPTION", ex.code());
    }

    @Test
    void negativeOptionPriceIsRefusedAndUnknownRowsAreNotFound() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 0, null, 1);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createOption(admin,
                g.groupId(), new CreateCustomizationOptionRequest("NEG-" + suffix(), "Negative",
                        new BigDecimal("-0.01"), 1)));
        assertEquals("INVALID_CUSTOMIZATION_OPTION", ex.code());

        assertThrows(ResourceNotFoundException.class, () -> service.updateOption(admin, UUID.randomUUID(),
                new UpdateCustomizationOptionRequest(0L, "Ghost", BigDecimal.ZERO, 0)));
        assertThrows(ResourceNotFoundException.class, () -> service.createOption(admin, UUID.randomUUID(),
                new CreateCustomizationOptionRequest("X-" + suffix(), "X", BigDecimal.ZERO, 0)));
    }

    @Test
    void staleOptionVersionIsConflict() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 0, null, 1);
        CustomizationOptionMasterResponse o = service.createOption(admin, g.groupId(),
                new CreateCustomizationOptionRequest("SV-" + suffix(), "V", BigDecimal.ZERO, 1));

        ConflictException ex = assertThrows(ConflictException.class, () -> service.updateOption(admin, o.optionId(),
                new UpdateCustomizationOptionRequest(o.version() + 3, "V2", BigDecimal.ZERO, 2)));
        assertEquals("CUSTOMIZATION_OPTION_VERSION_CONFLICT", ex.code());
    }

    @Test
    void reorderRewritesDisplayOrderAndRejectsIncompleteOrForeignSets() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 0, null, 1);
        var a = service.createOption(admin, g.groupId(),
                new CreateCustomizationOptionRequest("RA-" + suffix(), "A", BigDecimal.ZERO, 10));
        var b = service.createOption(admin, g.groupId(),
                new CreateCustomizationOptionRequest("RB-" + suffix(), "B", BigDecimal.ZERO, 20));
        var c = service.createOption(admin, g.groupId(),
                new CreateCustomizationOptionRequest("RC-" + suffix(), "C", BigDecimal.ZERO, 30));

        List<CustomizationOptionMasterResponse> reordered = service.reorderOptions(admin, g.groupId(),
                List.of(c.optionId(), a.optionId(), b.optionId()));
        assertEquals(List.of(c.optionId(), a.optionId(), b.optionId()),
                reordered.stream().map(option -> option.optionId()).toList());
        assertEquals(List.of(0, 1, 2), reordered.stream().map(option -> option.displayOrder()).toList());

        ValidationException partial = assertThrows(ValidationException.class,
                () -> service.reorderOptions(admin, g.groupId(), List.of(a.optionId(), b.optionId())));
        assertEquals("INVALID_REORDER_SET", partial.code());

        CustomizationGroupMasterResponse other = createGroup(SelectionType.MULTIPLE, 0, null, 2);
        var foreign = service.createOption(admin, other.groupId(),
                new CreateCustomizationOptionRequest("RF-" + suffix(), "F", BigDecimal.ZERO, 1));
        ValidationException bad = assertThrows(ValidationException.class, () -> service.reorderOptions(admin,
                g.groupId(), List.of(c.optionId(), a.optionId(), foreign.optionId())));
        assertEquals("INVALID_REORDER_SET", bad.code());
    }

    // -------------------------------------------------------------------- read

    @Test
    void listReportsInactiveMastersAndCarriesVersionsForTheEditor() {
        CustomizationGroupMasterResponse g = createGroup(SelectionType.MULTIPLE, 0, null, 1);
        service.createOption(admin, g.groupId(),
                new CreateCustomizationOptionRequest("LI-" + suffix(), "Hidden", BigDecimal.ZERO, 1));
        service.setGroupActive(admin, g.groupId(), false);

        CustomizationGroupMasterResponse seen = service.list(admin).stream()
                .filter(x -> x.groupId().equals(g.groupId())).findFirst().orElseThrow();
        assertFalse(seen.active(), "the editor read includes deactivated masters");
        assertEquals(1, seen.options().size());
        assertTrue(seen.options().get(0).version() >= 0);
    }

    @Test
    void callerWithoutProductManageIsForbiddenFromEveryMasterOperation() {
        CurrentActor customer = seedActor(CUSTOMER);
        assertThrows(ForbiddenException.class, () -> service.list(customer));
        assertThrows(ForbiddenException.class,
                () -> service.createGroup(customer, new CreateCustomizationGroupRequest(
                        "FB-" + suffix(), "Forbidden", SelectionType.SINGLE, 0, 1, 1)));
    }

    // ------------------------------------------------------------------ helpers

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private CustomizationGroupMasterResponse createGroup(SelectionType type, int min, Integer max, int order) {
        return createGroupRaw("MG-" + suffix(), "Master " + type + " " + suffix(), type, min, max, order);
    }

    private CustomizationGroupMasterResponse createGroupRaw(String code, String name, SelectionType type, int min,
            Integer max, int order) {
        return service.createGroup(admin, new CreateCustomizationGroupRequest(code, name, type, min, max, order));
    }

    private UUID newProduct() {
        UUID category = UUID.randomUUID();
        String catCode = "CMGC-" + suffix();
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name, "
                + "display_order, active, created_at, updated_at, version) values (?,?,?,10,true,now(),now(),0)",
                category, catCode, "Cat " + catCode);
        UUID id = UUID.randomUUID();
        String code = "CMGP-" + suffix();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, "
                + "version) values (?,?,?,?,?,'USD',3,0.10,true,true,now(),now(),0)", id, code, category,
                "CM Product", new BigDecimal("4.00"));
        return id;
    }

    /** Raw attachment row; V049 gives every override column a DB default. */
    private void attach(UUID product, UUID group, boolean required, Integer minOverride, Integer maxOverride) {
        jdbc.update("insert into catalog.product_customization_groups (product_id, group_id, required, "
                + "display_order, minimum_selections, maximum_selections) values (?,?,?,1,?,?)",
                product, group, required, minOverride, maxOverride);
    }

    private int auditRows(String action, UUID aggregateId) {
        Integer n = jdbc.queryForObject("select count(*) from audit.audit_logs where action=? and aggregate_id=?",
                Integer.class, action, aggregateId);
        return n == null ? 0 : n;
    }

    /** Same idiom as the attachment suites: a real role assignment behind a test auth context. */
    private CurrentActor seedActor(String roleId) {
        UUID userId = UUID.randomUUID();
        String email = "cmg-it-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "CMG Actor", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,null,?)", UUID.randomUUID(), userId, UUID.fromString(roleId),
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }
}
