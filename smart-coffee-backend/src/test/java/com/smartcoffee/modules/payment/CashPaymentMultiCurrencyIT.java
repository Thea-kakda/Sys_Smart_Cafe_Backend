package com.smartcoffee.modules.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.modules.payment.api.CashPaymentRequest;
import com.smartcoffee.modules.payment.api.PaymentResponse;
import com.smartcoffee.modules.payment.application.PaymentApplicationService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checkpoints 5C + 5D — authoritative multi-currency cash payment and its
 * immutable snapshot. Runs the real {@link PaymentApplicationService} against
 * the migrated database (V019 rate table, V021 snapshot columns) with strict
 * ddl-auto=validate. Proves the P0 security regression (a cashier can no
 * longer fabricate a rate to underpay), that settlement is backend-controlled,
 * and that the conversion trail is persisted and never mutated by later rate
 * changes, receipt rendering or idempotent replay.
 */
class CashPaymentMultiCurrencyIT extends IntegrationTestBase {

    private static final BigDecimal USD_TOTAL = new BigDecimal("3.50");
    private static final BigDecimal USD_TAX = new BigDecimal("0.35");
    private static final BigDecimal USD_SUBTOTAL = new BigDecimal("3.15");
    private static final UUID CASH_METHOD = UUID.fromString("80000000-0000-0000-0000-000000000001");

    @Autowired PaymentApplicationService service;
    @Autowired JdbcTemplate jdbc;

    private UUID store;
    private UUID user;
    private UUID order;

    // Rates are global (store_id null) so they must be cleared between tests,
    // otherwise a rate seeded by one test leaks into another's expectations.
    @BeforeEach
    void cleanRates() {
        jdbc.update("delete from payment.currency_exchange_rates");
    }

    // ------------------------------------------------------------- fixtures

    private void prepareUsdOrder() {
        store = createStore("USD");
        UUID[] uc = createUserWithProfile(store);
        user = uc[0];
        order = createOrder(uc[1], "USD", USD_SUBTOTAL, USD_TAX, USD_TOTAL);
    }

    private UUID createStore(String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,?, 'en-US', true, now(), now(), 0)",
                id, "CS" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "Cash Store", "Addr",
                "Asia/Phnom_Penh", currency);
        return id;
    }

    private UUID[] createUserWithProfile(UUID preferredStore) {
        UUID userId = UUID.randomUUID();
        String email = "cash-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Cash Customer", email, email, "en-US");
        UUID customerId = UUID.randomUUID();
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, preferred_store_id, "
                + "marketing_opt_in, created_at, updated_at, version) values (?,?,?,false,now(),now(),0)",
                customerId, userId, preferredStore);
        return new UUID[] { userId, customerId };
    }

    private UUID createOrder(UUID customerId, String currency, BigDecimal subtotal, BigDecimal tax,
            BigDecimal total) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, customer_id, source, "
                + "fulfillment_type, status, payment_status, currency_code, subtotal, discount_amount, tax_amount, "
                + "delivery_fee, rounding_amount, total_amount, total_quantity, ordered_at, updated_at, version) "
                + "values (?,?,?,?,'POS','DINE_IN','AWAITING_PAYMENT','PENDING',?,?,0,?,0,0,?,1,now(),now(),0)",
                orderId, "ORD-" + orderId, store, customerId, currency, subtotal, tax, total);
        return orderId;
    }

    private UUID seedRate(String base, String quote, String rate) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into payment.currency_exchange_rates (exchange_rate_id, store_id, base_currency, "
                + "quote_currency, rate, source, effective_from, effective_to, active, created_at, updated_at, version) "
                + "values (?,null,?,?,?,'MANUAL',?,null,true,now(),now(),0)",
                id, base, quote, new BigDecimal(rate), Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
        return id;
    }

    private PaymentResponse pay(String tenderCurrency, String received, String expectedRate) {
        return service.cash(user, UUID.randomUUID(), new CashPaymentRequest(order, store, new BigDecimal(received),
                tenderCurrency, expectedRate == null ? null : new BigDecimal(expectedRate)));
    }

    private static void eq(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but was null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private Map<String, Object> snapshotRow(UUID paymentId) {
        return jdbc.queryForMap("select tender_currency, tender_amount_received, exchange_rate_id, "
                + "applied_exchange_rate, rate_base_currency, rate_quote_currency, exchange_rate_inverted, "
                + "settlement_amount_received, settlement_change_amount, tender_change_amount, change_currency, "
                + "rounding_amount from payment.payments where payment_id = ?", paymentId);
    }

    // ------------------------------------------------------- settlement (5C)

    @Test
    void crossCurrencyTenderSettlesAtBackendRateAndSnapshots() {
        prepareUsdOrder();
        seedRate("USD", "KHR", "4100");
        // 20000 KHR / 4100 = 4.8780 -> 4.88 USD; change 1.38 USD -> 5658 KHR.
        var r = pay("KHR", "20000", null);

        assertEquals("USD", r.currencyCode());
        eq("4.88", r.amountReceived());
        eq("1.38", r.changeAmount());
        var c = r.conversion();
        assertNotNull(c);
        assertEquals("KHR", c.tenderCurrency());
        eq("20000", c.tenderAmountReceived());
        eq("4100", c.appliedExchangeRate());
        assertEquals("USD", c.rateBaseCurrency());
        assertEquals("KHR", c.rateQuoteCurrency());
        assertTrue(c.exchangeRateInverted());
        eq("4.88", c.settlementAmountReceived());
        eq("1.38", c.settlementChangeAmount());
        eq("5658", c.tenderChangeAmount());
        assertEquals("KHR", c.changeCurrency());
        assertNotNull(c.exchangeRateId());

        var row = snapshotRow(r.paymentId());
        assertEquals("KHR", row.get("tender_currency"));
        assertEquals(0, new BigDecimal("4100").compareTo((BigDecimal) row.get("applied_exchange_rate")));
        assertEquals(Boolean.TRUE, row.get("exchange_rate_inverted"));
        assertEquals("KHR", row.get("change_currency"));
    }

    @Test
    void sameCurrencyIsIdentitySnapshotNoRate() {
        prepareUsdOrder();
        var r = pay("USD", "5.00", null);
        eq("5.00", r.amountReceived());
        eq("1.50", r.changeAmount());
        var c = r.conversion();
        assertNotNull(c);
        assertNull(c.exchangeRateId(), "identity conversion uses no rate row");
        eq("1", c.appliedExchangeRate());
        assertFalse(c.exchangeRateInverted());
        assertEquals("USD", c.changeCurrency());
        eq("1.50", c.tenderChangeAmount());
    }

    @Test
    void insufficientCrossTenderRejected() {
        prepareUsdOrder();
        seedRate("USD", "KHR", "4100");
        // 10000 KHR -> 2.44 USD < 3.50 total.
        var ex = assertThrows(ConflictException.class, () -> pay("KHR", "10000", null));
        assertEquals("INSUFFICIENT_CASH_RECEIVED", ex.code());
    }

    @Test
    void insufficientSameCurrencyRejected() {
        prepareUsdOrder();
        var ex = assertThrows(ConflictException.class, () -> pay("USD", "3.00", null));
        assertEquals("INSUFFICIENT_CASH_RECEIVED", ex.code());
    }

    // ------------------------------------------------- security regression (5C)

    @Test
    void fabricatedClientRateCannotControlSettlement() {
        prepareUsdOrder();
        seedRate("USD", "KHR", "4100");
        // A cashier sends a plausible-but-false expected rate (1 KHR = 5000 USD)
        // hoping to inflate settlement. The backend ignores it for settlement and
        // rejects the mismatch instead of honouring it.
        var ex = assertThrows(ConflictException.class, () -> pay("KHR", "20000", "0.5"));
        assertEquals("RATE_STALE", ex.code());
        Integer paid = jdbc.queryForObject("select count(*) from payment.payments where order_id = ? and status = 'PAID'",
                Integer.class, order);
        assertEquals(0, paid, "fabricated rate must never settle an order");
    }

    @Test
    void arbitraryClientRateRejectedWhenSufficientTender() {
        prepareUsdOrder();
        seedRate("USD", "KHR", "4100");
        // Tender is sufficient, but the deprecated expected rate is wildly wrong.
        var ex = assertThrows(ConflictException.class, () -> pay("KHR", "20000", "0.001"));
        assertEquals("RATE_STALE", ex.code());
    }

    @Test
    void accurateExpectedRateWithinToleranceIsAccepted() {
        prepareUsdOrder();
        seedRate("USD", "KHR", "4100");
        // Correct preview (settlement/received ~ 0.000244) is within tolerance.
        var r = assertDoesNotThrow(() -> pay("KHR", "20000", "0.000244"));
        eq("4.88", r.amountReceived());
    }

    @Test
    void missingRateIsHardFailureForCrossCurrency() {
        prepareUsdOrder();
        // No USD->KHR rate seeded.
        var ex = assertThrows(ConflictException.class, () -> pay("KHR", "20000", null));
        assertEquals("RATE_UNAVAILABLE", ex.code());
        Integer rows = jdbc.queryForObject("select count(*) from payment.payments where order_id = ?", Integer.class,
                order);
        assertEquals(0, rows, "no payment may be created when the rate is unavailable");
    }

    // ---------------------------------------------------- snapshot (5D)

    @Test
    void snapshotIsImmutableAcrossLaterRateChanges() {
        prepareUsdOrder();
        UUID rateId = seedRate("USD", "KHR", "4100");
        var r = pay("KHR", "20000", null);
        var before = snapshotRow(r.paymentId());

        // The rate is restocked at a wildly different value afterwards.
        jdbc.update("update payment.currency_exchange_rates set rate = 9999 where exchange_rate_id = ?", rateId);
        seedRate("USD", "KHR", "1");

        var after = snapshotRow(r.paymentId());
        assertEquals(before.get("applied_exchange_rate"), after.get("applied_exchange_rate"));
        assertEquals(before.get("settlement_amount_received"), after.get("settlement_amount_received"));
        assertEquals(before.get("tender_change_amount"), after.get("tender_change_amount"));
        assertEquals(0, new BigDecimal("4100").compareTo((BigDecimal) after.get("applied_exchange_rate")),
                "old payment must keep the rate that applied at settlement, not a later one");
    }

    @Test
    void idempotentReplayReturnsOriginalSnapshot() {
        prepareUsdOrder();
        seedRate("USD", "KHR", "4100");
        var key = UUID.randomUUID();
        var first = service.cash(user, key, new CashPaymentRequest(order, store, new BigDecimal("20000"), "KHR", null));
        var replay = service.cash(user, key, new CashPaymentRequest(order, store, new BigDecimal("20000"), "KHR", null));

        assertEquals(first.paymentId(), replay.paymentId());
        eq("4100", replay.conversion().appliedExchangeRate());
        eq("5658", replay.conversion().tenderChangeAmount());
        eq("4.88", replay.amountReceived());
    }

    @Test
    void receiptSnapshotCarriesFullConversionTrail() {
        prepareUsdOrder();
        seedRate("USD", "KHR", "4100");
        var r = pay("KHR", "20000", null);
        Map<String, Object> f = jdbc.queryForMap("select "
                + "receipt_snapshot->>'tenderCurrency'          as tender_currency, "
                + "receipt_snapshot->>'changeCurrency'          as change_currency, "
                + "receipt_snapshot->>'rateDirection'           as rate_direction, "
                + "receipt_snapshot->>'settlementAmountReceived' as settlement, "
                + "receipt_snapshot->>'tenderChangeAmount'      as tender_change, "
                + "receipt_snapshot->>'taxAmount'               as tax, "
                + "receipt_snapshot->>'exchangeRateInverted'    as inverted, "
                + "jsonb_exists(receipt_snapshot, 'paidAt')          as has_paid_at, "
                + "jsonb_exists(receipt_snapshot, 'exchangeRateId')  as has_rate_id "
                + "from payment.receipts where payment_id = ?", r.paymentId());
        assertEquals("KHR", f.get("tender_currency"));
        assertEquals("KHR", f.get("change_currency"));
        assertEquals("USD->KHR", f.get("rate_direction"));
        assertEquals("4.88", f.get("settlement"));
        assertEquals("5658", f.get("tender_change"));
        assertEquals("0.3500", f.get("tax"));
        assertEquals("true", f.get("inverted"));
        assertEquals(Boolean.TRUE, f.get("has_paid_at"));
        assertEquals(Boolean.TRUE, f.get("has_rate_id"));
    }

    @Test
    void alreadyPaidOrderRejectedOnSecondAttempt() {
        prepareUsdOrder();
        pay("USD", "5.00", null);
        var ex = assertThrows(ConflictException.class, () -> pay("USD", "9.00", null));
        assertEquals("ORDER_ALREADY_PAID", ex.code());
    }

    @Test
    void cashMethodMustBeAvailable() {
        // Guards the fixture: without the seeded CASH method every test would be meaningless.
        Integer n = jdbc.queryForObject("select count(*) from payment.payment_methods where method_code = 'CASH' and active",
                Integer.class);
        assertEquals(1, n);
        assertNotNull(CASH_METHOD);
    }
}
