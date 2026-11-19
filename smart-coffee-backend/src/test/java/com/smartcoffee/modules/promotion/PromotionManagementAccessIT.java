package com.smartcoffee.modules.promotion;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.organization.api.CreateStoreRequest;
import com.smartcoffee.modules.organization.application.StoreManagementService;
import com.smartcoffee.modules.promotion.api.CreatePromotionRequest;
import com.smartcoffee.modules.promotion.api.PromotionAdminResponse;
import com.smartcoffee.modules.promotion.api.PromotionResponse;
import com.smartcoffee.modules.promotion.api.PromotionStatusRequest;
import com.smartcoffee.modules.promotion.api.PromotionValidationRequest;
import com.smartcoffee.modules.promotion.api.UpdatePromotionRequest;
import com.smartcoffee.modules.promotion.application.PromotionAdminService;
import com.smartcoffee.modules.promotion.application.PromotionEligibilityService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Promotions administration verification against a real migrated database
 * (V026 in play). Covers the §9 contract end to end: create with product/
 * category/store assignments, every authoritative validation rule, the
 * optimistic-lock edit path, the explicit status transition table, audit of
 * mutations, preservation of historical redemptions, and — critically — that
 * the customer-facing validate endpoint still behaves after all of this.
 * Service-level like the other management ITs; permission wiring is asserted
 * through freshly resolved actors.
 */
class PromotionManagementAccessIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    StoreManagementService storeManagement;
    @Autowired
    PromotionAdminService service;
    @Autowired
    PromotionEligibilityService eligibility;

    private static final String SUPER_ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000002";

    private UUID seedUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Test User", email, email, "en-US");
        return userId;
    }

    private UUID seedGlobalAdmin() {
        UUID userId = seedUser("admin-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, '" + SUPER_ADMIN_ROLE_ID + "', null, ?)", UUID.randomUUID(), userId,
                java.sql.Timestamp.from(Instant.now()));
        authenticate(userId);
        return userId;
    }

    private UUID roleIdByCode(String code) {
        return UUID.fromString(jdbc.queryForObject("select role_id from identity.roles where role_code = ?",
                String.class, code));
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private UUID newStore(CurrentActor actor) {
        String code = "PRM" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return storeManagement.create(actor, new CreateStoreRequest(code, "Promo Branch", "Addr", null,
                "Asia/Phnom_Penh", "USD", "km-KH", true)).storeId();
    }

    private UUID newCategory() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name) "
                + "values (?::uuid, ?, ?)", id.toString(), "CAT" + id.toString().substring(0, 8), "Test Category");
        return id;
    }

    private UUID newProduct(UUID categoryId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code) values (?::uuid, ?, ?::uuid, ?, 5, 'USD')", id.toString(),
                "PRD" + id.toString().substring(0, 8), categoryId.toString(), "Test Product");
        return id;
    }

    private static String uniqueCode(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace("-", "");
    }

    /** Valid defaults; callers override the single field under test. */
    private CreatePromotionRequest createReq(String code, String discountType, BigDecimal value,
            BigDecimal maximum, String currency, Instant start, Instant end, String status, Integer usageLimit,
            Integer perCustomer, List<UUID> storeIds, List<UUID> categoryIds, List<UUID> productIds) {
        return new CreatePromotionRequest(code, "IT Campaign", discountType, value, BigDecimal.valueOf(5), maximum,
                currency, start, end, status, usageLimit, perCustomer, false, false, 0, productIds, categoryIds,
                storeIds);
    }

    private CreatePromotionRequest validCreate(String code) {
        Instant now = Instant.now();
        return createReq(code, "PERCENTAGE", BigDecimal.TEN, null, null, now, now.plus(30, ChronoUnit.DAYS), "DRAFT",
                100, 1, null, null, null);
    }

    @Test
    void adminCreatesCampaignWithAssignmentsAndFullLifecycle() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID store = newStore(admin);
        UUID category = newCategory();
        UUID product = newProduct(category);

        Instant now = Instant.now();
        PromotionAdminResponse created = service.create(admin,
                createReq(uniqueCode("LFC"), "PERCENTAGE", BigDecimal.TEN, BigDecimal.valueOf(50), "USD",
                        now, now.plus(10, ChronoUnit.DAYS), "DRAFT", 100, 2, List.of(store), List.of(category),
                        List.of(product)));
        assertEquals("DRAFT", created.status());
        assertEquals(0, created.version());
        assertEquals(0, created.usageCount());
        assertEquals(List.of(store), created.storeIds());
        assertEquals(List.of(category), created.categoryIds());
        assertEquals(List.of(product), created.productIds());

        // Duplicate code is refused.
        ConflictException dup = assertThrows(ConflictException.class,
                () -> service.create(admin, validCreate(created.promotionCode())));
        assertEquals("PROMOTION_CODE_TAKEN", dup.code());

        // DRAFT -> PAUSED is not in the transition table.
        ValidationException badMove = assertThrows(ValidationException.class, () -> service.changeStatus(admin,
                created.promotionId(), new PromotionStatusRequest("PAUSED", created.version(), null)));
        assertEquals("INVALID_STATUS_TRANSITION", badMove.code());

        // DRAFT -> ACTIVE with the correct version.
        PromotionAdminResponse active = service.changeStatus(admin,
                created.promotionId(), new PromotionStatusRequest("ACTIVE", created.version(), "launch"));
        assertEquals("ACTIVE", active.status());
        assertEquals(1, active.version());

        // Stale version on the next move is refused.
        ConflictException stale = assertThrows(ConflictException.class, () -> service.changeStatus(admin,
                created.promotionId(), new PromotionStatusRequest("PAUSED", 0, null)));
        assertEquals("VERSION_CONFLICT", stale.code());

        // Definition update keeps code, usage count, and progresses the version.
        PromotionAdminResponse edited = service.update(admin, created.promotionId(), new UpdatePromotionRequest(
                "Renamed Campaign", "PERCENTAGE", BigDecimal.valueOf(20), BigDecimal.valueOf(8), null, null,
                active.startsAt(), active.endsAt(), 100, 2, false, false, 0, null, null, null, active.version()));
        assertEquals(created.promotionCode(), edited.promotionCode());
        assertEquals(0, new BigDecimal("20").compareTo(edited.discountValue()));
        assertEquals(0, edited.usageCount());
        assertEquals(List.of(store), edited.storeIds()); // null lists leave assignments untouched

        // ACTIVE -> PAUSED -> CANCELLED, then the terminal state refuses reactivation.
        PromotionAdminResponse paused = service.changeStatus(admin, created.promotionId(),
                new PromotionStatusRequest("PAUSED", edited.version(), null));
        PromotionAdminResponse cancelled = service.changeStatus(admin, created.promotionId(),
                new PromotionStatusRequest("CANCELLED", paused.version(), "end of campaign"));
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("CANCELLED", service.detail(created.promotionId()).status());
        ValidationException revive = assertThrows(ValidationException.class, () -> service.changeStatus(admin,
                created.promotionId(), new PromotionStatusRequest("ACTIVE", cancelled.version(), null)));
        assertEquals("INVALID_STATUS_TRANSITION", revive.code());

        // Every mutation audited: create + 4 status attempts that succeeded (ACTIVE, PAUSED, CANCELLED) + update.
        Integer audits = jdbc.queryForObject(
                "select count(*) from audit.audit_logs where aggregate_type='PROMOTION' and aggregate_id=?::uuid",
                Integer.class, created.promotionId().toString());
        assertTrue(audits != null && audits >= 5, "expected >=5 audit rows, got " + audits);

        SecurityContextHolder.clearContext();
    }

    @Test
    void createEnforcesEveryDomainValidation() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        Instant now = Instant.now();

        // Percentage above 100.
        ValidationException pct = assertThrows(ValidationException.class, () -> service.create(admin,
                createReq(uniqueCode("V"), "PERCENTAGE", BigDecimal.valueOf(150), null, null, now,
                        now.plus(1, ChronoUnit.DAYS), "DRAFT", null, null, null, null, null)));
        assertEquals("INVALID_DISCOUNT_VALUE", pct.code());

        // Fixed amount without a currency (monetary discount requires one).
        ValidationException noCur = assertThrows(ValidationException.class, () -> service.create(admin,
                createReq(uniqueCode("V"), "FIXED_AMOUNT", BigDecimal.valueOf(5), null, null, now,
                        now.plus(1, ChronoUnit.DAYS), "DRAFT", null, null, null, null, null)));
        assertEquals("MISSING_CURRENCY", noCur.code());

        // Unsupported currency.
        ValidationException badCur = assertThrows(ValidationException.class, () -> service.create(admin,
                createReq(uniqueCode("V"), "FIXED_AMOUNT", BigDecimal.valueOf(5), null, "EUR", now,
                        now.plus(1, ChronoUnit.DAYS), "DRAFT", null, null, null, null, null)));
        assertEquals("UNSUPPORTED_CURRENCY", badCur.code());

        // End before start.
        ValidationException period = assertThrows(ValidationException.class, () -> service.create(admin,
                createReq(uniqueCode("V"), "PERCENTAGE", BigDecimal.TEN, null, null, now,
                        now.minus(1, ChronoUnit.DAYS), "DRAFT", null, null, null, null, null)));
        assertEquals("INVALID_PERIOD", period.code());

        // Per-customer limit above the total usage limit.
        ValidationException limits = assertThrows(ValidationException.class, () -> service.create(admin,
                createReq(uniqueCode("V"), "PERCENTAGE", BigDecimal.TEN, null, null, now,
                        now.plus(1, ChronoUnit.DAYS), "DRAFT", 5, 10, null, null, null)));
        assertEquals("INVALID_PER_CUSTOMER_LIMIT", limits.code());

        // Unknown discount type + malformed code.
        ValidationException type = assertThrows(ValidationException.class, () -> service.create(admin,
                createReq(uniqueCode("V"), "BOGO", BigDecimal.TEN, null, null, now, now.plus(1, ChronoUnit.DAYS),
                        "DRAFT", null, null, null, null, null)));
        assertEquals("INVALID_DISCOUNT_TYPE", type.code());
        ValidationException code = assertThrows(ValidationException.class, () -> service.create(admin,
                createReq("x!", "PERCENTAGE", BigDecimal.TEN, null, null, now, now.plus(1, ChronoUnit.DAYS), "DRAFT",
                        null, null, null, null, null)));
        assertEquals("INVALID_PROMOTION_CODE", code.code());

        // Nonexistent store target.
        ValidationException target = assertThrows(ValidationException.class, () -> service.create(admin,
                createReq(uniqueCode("V"), "PERCENTAGE", BigDecimal.TEN, null, null, now, now.plus(1, ChronoUnit.DAYS),
                        "DRAFT", null, null, List.of(UUID.randomUUID()), null, null)));
        assertEquals("TARGET_NOT_FOUND", target.code());

        // Creating directly as ACTIVE with an already-expired period.
        ValidationException expired = assertThrows(ValidationException.class, () -> service.create(admin,
                createReq(uniqueCode("V"), "PERCENTAGE", BigDecimal.TEN, null, null,
                        now.minus(5, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), "ACTIVE", null, null, null,
                        null, null)));
        assertEquals("INVALID_PERIOD", expired.code());

        SecurityContextHolder.clearContext();
    }

    @Test
    void listAndDetailAreBoundedAndCoverSeededCampaign() {
        seedGlobalAdmin();
        actors.require();

        // WELCOME10 is seeded by V009 and must be visible to administration.
        var page = service.list("ACTIVE", 0, 100);
        assertTrue(page.getContent().stream().anyMatch(p -> "WELCOME10".equals(p.promotionCode())));
        assertTrue(page.getContent().stream().allMatch(p -> "ACTIVE".equals(p.status())));

        // Unbounded size is clamped.
        assertEquals(100, service.list(null, 0, 1000).getSize());

        // Bad status filter is a clean validation error, not a query crash.
        assertEquals("INVALID_STATUS", assertThrows(ValidationException.class,
                () -> service.list("BOGUS", 0, 20)).code());

        // Detail exposes assignments as empty lists for the unassigned seed.
        PromotionAdminResponse welcome = page.getContent().stream()
                .filter(p -> "WELCOME10".equals(p.promotionCode())).findFirst().orElseThrow();
        PromotionAdminResponse full = service.detail(welcome.promotionId());
        assertEquals(List.of(), full.storeIds());
        assertNotNull(full.startsAt());

        assertThrows(ResourceNotFoundException.class, () -> service.detail(UUID.randomUUID()));

        SecurityContextHolder.clearContext();
    }

    @Test
    void definitionEditsNeverRewriteRedemptionHistory() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID store = newStore(admin);
        Instant now = Instant.now();

        PromotionAdminResponse promo = service.create(admin, validCreate(uniqueCode("HIS")));

        // A real order + reserved redemption, and a usage count booked by the
        // claim engine (never writable through the admin API).
        UUID orderId = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, source, fulfillment_type, status, "
                + "payment_status, currency_code, subtotal, total_amount, total_quantity, ordered_at, updated_at) "
                + "values (?::uuid, 'HIS-1', ?::uuid, 'POS', 'DINE_IN', 'CONFIRMED', 'PAID', 'USD', 20, 18, 2, "
                + "?::timestamptz, ?::timestamptz)", orderId.toString(), store.toString(), now.toString(),
                now.toString());
        UUID redemptionId = UUID.randomUUID();
        jdbc.update("insert into promotion.promotion_redemptions (redemption_id, promotion_id, order_id, store_id, "
                + "status, discount_amount, currency_code, reserved_at) values (?::uuid, ?::uuid, ?::uuid, ?::uuid, "
                + "'RESERVED', 2, 'USD', now())", redemptionId, promo.promotionId(), orderId, store);
        jdbc.update("update promotion.promotions set usage_count=1 where promotion_id=?::uuid",
                promo.promotionId().toString());

        PromotionAdminResponse edited = service.update(admin, promo.promotionId(), new UpdatePromotionRequest(
                "Renamed But History Intact", "PERCENTAGE", BigDecimal.valueOf(12), BigDecimal.valueOf(5), null,
                null, now, now.plus(60, ChronoUnit.DAYS), 100, 1, false, false, 0, null, null, null,
                promo.version()));

        assertEquals(1, edited.usageCount(), "usage_count must survive definition edits");
        BigDecimal kept = jdbc.queryForObject(
                "select discount_amount from promotion.promotion_redemptions where redemption_id=?::uuid",
                BigDecimal.class, redemptionId.toString());
        assertEquals(0, new BigDecimal("2").compareTo(kept));

        SecurityContextHolder.clearContext();
    }

    @Test
    void customerFacingValidateEndpointStillWorks() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID store = newStore(admin);

        // The seeded WELCOME10 (10%, min 5 USD, ACTIVE) must still validate.
        PromotionResponse ok = eligibility.validate(new PromotionValidationRequest(store, UUID.randomUUID(),
                UUID.randomUUID(), "welcome10", BigDecimal.valueOf(20), "USD", null, null));
        assertTrue(ok.eligible());
        assertEquals("WELCOME10", ok.code());
        assertEquals(0, new BigDecimal("2").compareTo(ok.discountAmount()));

        // Unknown codes keep the existing soft-failure contract.
        PromotionResponse missing = eligibility.validate(new PromotionValidationRequest(store, UUID.randomUUID(),
                UUID.randomUUID(), "NOPE-" + UUID.randomUUID(), BigDecimal.valueOf(20), "USD", null, null));
        assertFalse(missing.eligible());
        assertEquals("PROMOTION_NOT_FOUND", missing.reason());

        SecurityContextHolder.clearContext();
    }

    @Test
    void promotionPermissionsAreSeededPerRole() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        assertTrue(admin.hasPermission(PermissionCodes.PROMOTION_READ));
        assertTrue(admin.hasPermission(PermissionCodes.PROMOTION_MANAGE));

        UUID store = newStore(admin);

        UUID mgr = seedUser("promomgr-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), mgr, roleIdByCode("STORE_MANAGER"),
                store.toString());
        authenticate(mgr);
        CurrentActor manager = actors.require();
        assertTrue(manager.hasPermission(PermissionCodes.PROMOTION_READ), "V026 grants managers read-only visibility");
        assertFalse(manager.hasPermission(PermissionCodes.PROMOTION_MANAGE));

        UUID barista = seedUser("promobar-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), barista, roleIdByCode("BARISTA"),
                store.toString());
        authenticate(barista);
        CurrentActor worker = actors.require();
        assertFalse(worker.hasPermission(PermissionCodes.PROMOTION_READ));
        assertFalse(worker.hasPermission(PermissionCodes.PROMOTION_MANAGE));

        SecurityContextHolder.clearContext();
    }
}
