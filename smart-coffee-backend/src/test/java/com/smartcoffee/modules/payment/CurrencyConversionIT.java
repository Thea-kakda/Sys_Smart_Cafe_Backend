package com.smartcoffee.modules.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.UnauthorizedException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.payment.application.CurrencyConversionService;
import com.smartcoffee.modules.payment.application.CurrencyConversionService.ConversionResult;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Checkpoint 5B — authoritative currency conversion preview. Runs against the
 * real migrated database (V019 rate table + V020 permissions) with strict
 * ddl-auto=validate. Exercises the single conversion engine both the preview
 * endpoint and (later) payment settlement depend on.
 */
class CurrencyConversionIT extends IntegrationTestBase {

    private static final UUID CASHIER = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID BARISTA = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrencyConversionService conversions;
    @Autowired CurrentActorProvider actors;

    private UUID store;
    private CurrentActor cashier;

    @BeforeEach
    void reset() {
        jdbc.update("delete from payment.currency_exchange_rates");
        store = createStore();
        cashier = asRole(CASHIER, store);
    }

    // ------------------------------------------------------------- fixtures

    private UUID createStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,'Conv Store','Addr','Asia/Phnom_Penh','USD','en-US',true,now(),now(),0)",
                id, "CV" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        return id;
    }

    private CurrentActor asRole(UUID roleId, UUID storeId) {
        UUID userId = UUID.randomUUID();
        String email = "u-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "IT User", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,?,?)", UUID.randomUUID(), userId, roleId, storeId, java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    /** base -> quote at `rate`, storeId null = global, optional end (exclusive). */
    private void seedRate(UUID storeId, String base, String quote, String rate, Instant from, Instant to) {
        jdbc.update("insert into payment.currency_exchange_rates (exchange_rate_id, store_id, base_currency, "
                + "quote_currency, rate, source, effective_from, effective_to, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'MANUAL', ?, ?, true, now(), now(), 0)",
                UUID.randomUUID(), storeId, base, quote, new BigDecimal(rate),
                java.sql.Timestamp.from(from), to == null ? null : java.sql.Timestamp.from(to));
    }

    private ConversionResult conv(String from, String to, String amount) {
        return conversions.convert(store, from, to, new BigDecimal(amount), Instant.now());
    }

    // --------------------------------------------------------- conversions

    @Test
    void usdToKhrDirect() {
        seedRate(null, "USD", "KHR", "4100", Instant.now().minus(1, ChronoUnit.DAYS), null);
        var r = conv("USD", "KHR", "3.00");
        assertEquals(0, new BigDecimal("12300").compareTo(r.targetAmount()));
        assertEquals(0, r.targetAmount().scale());
        assertEquals(0, new BigDecimal("4100").compareTo(r.appliedRate()));
        assertFalse(r.inversionUsed());
        assertEquals("USD", r.baseCurrency().name());
        assertEquals("KHR", r.quoteCurrency().name());
        assertNotNull(r.exchangeRateId());
    }

    @Test
    void khrToUsdInverted() {
        seedRate(null, "USD", "KHR", "4100", Instant.now().minus(1, ChronoUnit.DAYS), null);
        var r = conv("KHR", "USD", "4100");
        assertEquals(0, new BigDecimal("1.00").compareTo(r.targetAmount()));
        assertEquals(2, r.targetAmount().scale());
        assertTrue(r.inversionUsed());
    }

    @Test
    void sameCurrencyIdentity() {
        var r = conv("USD", "USD", "5.00");
        assertNull(r.exchangeRateId());
        assertEquals(0, new BigDecimal("1").compareTo(r.appliedRate()));
        assertEquals("IDENTITY", r.rateSource());
        assertFalse(r.inversionUsed());
        assertEquals(0, new BigDecimal("5.00").compareTo(r.targetAmount()));
    }

    @Test
    void zeroAmountAllowed() {
        seedRate(null, "USD", "KHR", "4100", Instant.now().minus(1, ChronoUnit.DAYS), null);
        var r = conv("USD", "KHR", "0.00");
        assertEquals(0, r.targetAmount().signum());
    }

    @Test
    void negativeAmountRejected() {
        var ex = assertThrows(ValidationException.class, () -> conv("USD", "KHR", "-1"));
        assertEquals("NEGATIVE_AMOUNT", ex.code());
    }

    @Test
    void unsupportedCurrencyRejected() {
        var ex = assertThrows(ValidationException.class, () -> conv("USD", "EUR", "1"));
        assertEquals("UNSUPPORTED_CURRENCY", ex.code());
    }

    @Test
    void missingRateRejected() {
        // No rate seeded at all.
        var ex = assertThrows(ConflictException.class, () -> conv("USD", "KHR", "1"));
        assertEquals("RATE_UNAVAILABLE", ex.code());
    }

    @Test
    void expiredRateRejected() {
        Instant past = Instant.now().minus(10, ChronoUnit.DAYS);
        seedRate(null, "USD", "KHR", "4100", past.minus(1, ChronoUnit.DAYS), past);
        var ex = assertThrows(ConflictException.class, () -> conv("USD", "KHR", "1"));
        assertEquals("RATE_UNAVAILABLE", ex.code());
    }

    @Test
    void futureRateRejected() {
        Instant future = Instant.now().plus(5, ChronoUnit.DAYS);
        seedRate(null, "USD", "KHR", "4100", future, null);
        var ex = assertThrows(ConflictException.class, () -> conv("USD", "KHR", "1"));
        assertEquals("RATE_UNAVAILABLE", ex.code());
    }

    @Test
    void branchRateTakesPrecedenceOverGlobal() {
        seedRate(null, "USD", "KHR", "4000", Instant.now().minus(1, ChronoUnit.DAYS), null);
        seedRate(store, "USD", "KHR", "4100", Instant.now().minus(1, ChronoUnit.DAYS), null);
        var r = conv("USD", "KHR", "3.00");
        assertEquals(0, new BigDecimal("4100").compareTo(r.appliedRate()));
        assertEquals(0, new BigDecimal("12300").compareTo(r.targetAmount()));
    }

    @Test
    void fallsBackToGlobalWhenNoBranchRate() {
        seedRate(null, "USD", "KHR", "4000", Instant.now().minus(1, ChronoUnit.DAYS), null);
        var r = conv("USD", "KHR", "3.00");
        assertEquals(0, new BigDecimal("4000").compareTo(r.appliedRate()));
        assertEquals(0, new BigDecimal("12000").compareTo(r.targetAmount()));
    }

    @Test
    void appliesHalfUpRoundingToTargetScale() {
        seedRate(store, "USD", "KHR", "3.0", Instant.now().minus(1, ChronoUnit.DAYS), null);
        // 2.5 USD * 3 = 7.5 KHR -> HALF_UP to 0 dp -> 8
        var r = conv("USD", "KHR", "2.5");
        assertEquals(0, new BigDecimal("8").compareTo(r.targetAmount()));
        assertEquals("HALF_UP", r.roundingMode().name());
        assertEquals(0, r.targetScale());
    }

    // ---------------------------------------------------------- guardrails

    @Test
    void forbiddenForUnassignedBranch() {
        UUID otherStore = createStore();
        CurrentActor scoped = asRole(CASHIER, otherStore);
        var ex = assertThrows(ForbiddenException.class,
                () -> conversions.preview(scoped, store, "USD", "KHR", BigDecimal.ONE));
        assertEquals("STORE_ACCESS_DENIED", ex.code());
    }

    @Test
    void previewRequiresConversionPermission() {
        // Barista assigned to THIS store has store access but no conversion preview grant.
        CurrentActor barista = asRole(BARISTA, store);
        assertTrue(barista.canAccessStore(store));
        assertFalse(barista.hasPermission("currency-conversion:preview"));
        var ex = assertThrows(ForbiddenException.class,
                () -> conversions.preview(barista, store, "USD", "KHR", BigDecimal.ONE));
        assertEquals("AUTH_FORBIDDEN", ex.code());
    }

    @Test
    void unauthenticatedResolvesToNoActor() {
        SecurityContextHolder.clearContext();
        assertThrows(UnauthorizedException.class, () -> actors.require());
    }

    @Test
    void previewIsReadonlyAndSelectsActiveRate() {
        seedRate(store, "USD", "KHR", "4100", Instant.now().minus(1, ChronoUnit.DAYS), null);
        Integer before = jdbc.queryForObject("select count(*) from payment.currency_exchange_rates", Integer.class);
        var r = conversions.preview(cashier, store, "USD", "KHR", new BigDecimal("2.00"));
        Integer after = jdbc.queryForObject("select count(*) from payment.currency_exchange_rates", Integer.class);
        assertEquals(before, after, "preview must not create or mutate rates");
        assertEquals(0, new BigDecimal("8200").compareTo(r.targetAmount()));
    }
}
