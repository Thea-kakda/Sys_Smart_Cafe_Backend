package com.smartcoffee.modules.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.payment.api.CreateExchangeRateRequest;
import com.smartcoffee.modules.payment.api.ExchangeRateResponse;
import com.smartcoffee.modules.payment.application.CurrencyConversionService;
import com.smartcoffee.modules.payment.application.ExchangeRateManagementService;
import com.smartcoffee.modules.payment.persistence.CurrencyExchangeRateRepository;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Checkpoint 10 — exchange-rate administration. Runs against the real migrated
 * database (V019 rate table + V020 permissions) with strict ddl-auto=validate.
 * Proves the management path that V019 deliberately leaves unseeded: an operator
 * creates/supersedes/retires rates, and those rates then drive the conversion
 * engine built in 5B. Authorization, scope and optimistic locking are exercised
 * end-to-end, and the sentinel listing is asserted with no filters (the null
 * -parameter bytea regression).
 */
class ExchangeRateManagementIT extends IntegrationTestBase {

    private static final UUID SUPER_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID STORE_MANAGER = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID CASHIER = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID BARISTA = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @Autowired JdbcTemplate jdbc;
    @Autowired ExchangeRateManagementService management;
    @Autowired CurrencyConversionService conversions;
    @Autowired CurrencyExchangeRateRepository rateRepo;
    @Autowired CurrentActorProvider actors;

    private UUID store;
    private CurrentActor admin;

    @BeforeEach
    void reset() {
        jdbc.update("delete from payment.currency_exchange_rates");
        store = createStore();
        admin = asRole(SUPER_ADMIN, null); // global admin: store-NULL assignment
    }

    // ------------------------------------------------------------- fixtures

    private UUID createStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'Rate Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)",
                id, "RT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        return id;
    }

    private CurrentActor asRole(UUID roleId, UUID storeId) {
        UUID userId = UUID.randomUUID();
        String email = "u-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "IT User", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,?,?)", UUID.randomUUID(), userId, roleId, storeId,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    // ------------------------------------------------------------- create

    @Test
    void globalAdminCreatesGlobalRateAndItDrivesConversion() {
        ExchangeRateResponse created = management.create(admin,
                new CreateExchangeRateRequest(null, "USD", "KHR", new BigDecimal("4100"), "MANUAL", null, null));

        assertEquals(0L, created.version());
        assertTrue(created.active());
        assertEquals("GLOBAL", created.scope());
        assertNull(created.storeId());
        assertNotNull(created.createdByUserId());
        assertEquals(0, new BigDecimal("4100").compareTo(created.rate()));

        // The created rate is authoritative for the conversion engine (5B link).
        var result = conversions.convert(store, "USD", "KHR", new BigDecimal("2.00"), Instant.now());
        assertEquals(0, new BigDecimal("8200").compareTo(result.targetAmount()));
        assertEquals(created.exchangeRateId(), result.exchangeRateId());
    }

    @Test
    void branchManagerCreatesScopedRateAndSupersedesPredecessor() {
        CurrentActor manager = asRole(STORE_MANAGER, store);
        Instant t1 = Instant.now().minus(5, ChronoUnit.DAYS);

        ExchangeRateResponse first = management.create(manager,
                new CreateExchangeRateRequest(store, "USD", "KHR", new BigDecimal("4000"), "MANUAL", t1, null));
        assertEquals("BRANCH", first.scope());

        Instant t2 = Instant.now().minus(1, ChronoUnit.DAYS);
        ExchangeRateResponse second = management.create(manager,
                new CreateExchangeRateRequest(store, "USD", "KHR", new BigDecimal("4100"), "MANUAL", t2, null));

        // History retained, not edited: the old row is closed, not deleted.
        var reopened = rateRepo.findById(first.exchangeRateId()).orElseThrow();
        Instant closed = reopened.effectiveTo();
        assertNotNull(closed, "predecessor must be closed at the new window start");
        // Postgres timestamptz is microsecond precision; compare whole seconds
        // so the assertion reflects the business rule (closed at the new start),
        // not sub-millisecond storage truncation.
        assertEquals(t2.getEpochSecond(), closed.getEpochSecond(),
                "predecessor closed at the new rate's effectiveFrom");
        assertTrue(rateRepo.findById(second.exchangeRateId()).orElseThrow().effectiveTo() == null);

        // The newest window is what the engine selects now.
        var result = conversions.convert(store, "USD", "KHR", new BigDecimal("3.00"), Instant.now());
        assertEquals(0, new BigDecimal("4100").compareTo(result.appliedRate()));
    }

    @Test
    void overlappingEarlierWindowIsRejected() {
        Instant later = Instant.now().minus(1, ChronoUnit.DAYS);
        management.create(admin, new CreateExchangeRateRequest(null, "USD", "KHR", new BigDecimal("4100"), "MANUAL",
                later, null));
        // Inserting an open rate that starts BEFORE the existing one cannot be
        // expressed as a clean supersede (closeAt requires the close after start).
        Instant earlier = Instant.now().minus(3, ChronoUnit.DAYS);
        ConflictException ex = assertThrows(ConflictException.class, () -> management.create(admin,
                new CreateExchangeRateRequest(null, "USD", "KHR", new BigDecimal("4050"), "MANUAL", earlier, null)));
        assertEquals("RATE_PERIOD_OVERLAP", ex.code());
    }

    // ---------------------------------------------------------- deactivate

    @Test
    void deactivationUsesOptimisticLockAndPreservesHistory() {
        ExchangeRateResponse created = management.create(admin,
                new CreateExchangeRateRequest(null, "USD", "KHR", new BigDecimal("4100"), "MANUAL", null, null));

        ConflictException stale = assertThrows(ConflictException.class,
                () -> management.deactivate(admin, created.exchangeRateId(), 99L));
        assertEquals("VERSION_CONFLICT", stale.code());

        ExchangeRateResponse retired = management.deactivate(admin, created.exchangeRateId(), created.version());
        assertFalse(retired.active());
        // Row still present (append-only history), but no longer selectable.
        assertTrue(rateRepo.findById(created.exchangeRateId()).isPresent());
        assertThrows(ConflictException.class,
                () -> conversions.convert(store, "USD", "KHR", BigDecimal.ONE, Instant.now()));
    }

    // -------------------------------------------------------- authorization

    @Test
    void cashierCanReadButNotManage() {
        management.create(admin, new CreateExchangeRateRequest(null, "USD", "KHR", new BigDecimal("4100"), "MANUAL",
                null, null));
        CurrentActor cashier = asRole(CASHIER, store);

        Page<ExchangeRateResponse> page = management.list(cashier, null, null, "ALL", null, null, 0, 20);
        assertTrue(page.getContent().stream().anyMatch(r -> r.scope().equals("GLOBAL")));

        assertThrows(ForbiddenException.class, () -> management.create(cashier,
                new CreateExchangeRateRequest(store, "USD", "KHR", new BigDecimal("4200"), "MANUAL", null, null)));
    }

    @Test
    void actorWithoutRatePermissionCannotList() {
        CurrentActor barista = asRole(BARISTA, store);
        assertThrows(ForbiddenException.class,
                () -> management.list(barista, null, null, "ALL", null, null, 0, 20));
    }

    @Test
    void branchScopedManagerCannotCreateGlobalRate() {
        CurrentActor manager = asRole(STORE_MANAGER, store);
        assertFalse(manager.globalAccess());
        ForbiddenException ex = assertThrows(ForbiddenException.class, () -> management.create(manager,
                new CreateExchangeRateRequest(null, "USD", "KHR", new BigDecimal("4100"), "MANUAL", null, null)));
        assertEquals("GLOBAL_RATE_ADMIN_ONLY", ex.code());
    }

    @Test
    void branchManagerCannotTouchAnotherBranchRate() {
        UUID other = createStore();
        CurrentActor manager = asRole(STORE_MANAGER, store);
        assertThrows(ForbiddenException.class, () -> management.create(manager,
                new CreateExchangeRateRequest(other, "USD", "KHR", new BigDecimal("4100"), "MANUAL", null, null)));
    }

    // ------------------------------------------------------------- listing

    @Test
    void sentinelListingWorksWithNoFiltersAndFiltersByScope() {
        management.create(admin,
                new CreateExchangeRateRequest(null, "USD", "KHR", new BigDecimal("4000"), "MANUAL", null, null));
        management.create(admin,
                new CreateExchangeRateRequest(store, "USD", "KHR", new BigDecimal("4100"), "MANUAL", null, null));
        management.create(admin,
                new CreateExchangeRateRequest(store, "KHR", "USD", new BigDecimal("0.00024"), "MANUAL", null, null));

        // No filters at all: the null-parameter bytea regression path.
        Page<ExchangeRateResponse> all = management.list(admin, null, null, null, null, null, 0, 50);
        assertEquals(3L, all.getTotalElements());

        assertEquals(1L, management.list(admin, null, null, "GLOBAL", null, null, 0, 50).getTotalElements());
        assertEquals(2L, management.list(admin, null, null, "BRANCH", null, null, 0, 50).getTotalElements());
        // Pair filter.
        assertTrue(management.list(admin, "KHR", "USD", "ALL", null, null, 0, 50).getContent().stream()
                .allMatch(r -> r.baseCurrency().equals("KHR") && r.quoteCurrency().equals("USD")));
        // Store filter narrows to that branch's two rates.
        assertEquals(2L, management.list(admin, null, null, "ALL", store, null, 0, 50).getTotalElements());
    }

    @Test
    void invalidDomainInputIsRejectedAtCreate() {
        assertThrows(com.smartcoffee.common.exception.ValidationException.class, () -> management.create(admin,
                new CreateExchangeRateRequest(null, "USD", "USD", new BigDecimal("1"), "MANUAL", null, null)));
        assertThrows(com.smartcoffee.common.exception.ValidationException.class, () -> management.create(admin,
                new CreateExchangeRateRequest(null, "USD", "KHR", new BigDecimal("-5"), "MANUAL", null, null)));
    }
}
