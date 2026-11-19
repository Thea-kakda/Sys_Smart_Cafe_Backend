package com.smartcoffee.modules.payment.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * Checkpoint 5A — exchange-rate domain model invariants. Pure unit tests: the
 * model rejects invalid quotes at construction so they can never be persisted.
 */
class CurrencyExchangeRateTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    private CurrencyExchangeRate usdToKhr(String rate) {
        return CurrencyExchangeRate.create(null, Currency.USD, Currency.KHR, new BigDecimal(rate), "MANUAL", FROM,
                null, null);
    }

    @Test
    void createsGlobalDirectedRate() {
        var r = usdToKhr("4100.0000000000");
        assertTrue(r.isGlobal());
        assertEquals(Currency.USD, r.baseCurrency());
        assertEquals(Currency.KHR, r.quoteCurrency());
        assertEquals(0, new BigDecimal("4100").compareTo(r.rate()));
        assertTrue(r.active());
    }

    @Test
    void rejectsIdenticalCurrencies() {
        var ex = assertThrows(ValidationException.class,
                () -> CurrencyExchangeRate.create(null, Currency.USD, Currency.USD, BigDecimal.ONE, "MANUAL", FROM,
                        null, null));
        assertEquals("IDENTICAL_CURRENCY", ex.code());
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThrows(ValidationException.class, () -> usdToKhr("0"));
        assertThrows(ValidationException.class, () -> usdToKhr("-1"));
    }

    @Test
    void rejectsRateBeyondPrecision() {
        // 11 fractional digits exceed numeric(20,10)
        assertThrows(ValidationException.class, () -> usdToKhr("1.00000000001"));
    }

    @Test
    void rejectsMissingSourceAndEffectiveFrom() {
        assertThrows(ValidationException.class, () -> CurrencyExchangeRate.create(null, Currency.USD, Currency.KHR,
                BigDecimal.ONE, "  ", FROM, null, null));
        assertThrows(ValidationException.class, () -> CurrencyExchangeRate.create(null, Currency.USD, Currency.KHR,
                BigDecimal.ONE, "MANUAL", null, null, null));
    }

    @Test
    void rejectsEffectiveBeforeFrom() {
        var ex = assertThrows(ValidationException.class, () -> CurrencyExchangeRate.create(null, Currency.USD,
                Currency.KHR, BigDecimal.ONE, "MANUAL", FROM, FROM.minusSeconds(1), null));
        assertEquals("INVALID_EFFECTIVE_PERIOD", ex.code());
    }

    @Test
    void coversOnlyWithinActiveWindow() {
        Instant to = FROM.plus(10, ChronoUnit.DAYS);
        var r = CurrencyExchangeRate.create(null, Currency.USD, Currency.KHR, new BigDecimal("4100"), "MANUAL", FROM,
                to, null);
        assertTrue(r.covers(FROM));
        assertTrue(r.covers(to.minusMillis(1)));
        assertFalse(r.covers(to), "effectiveTo is exclusive");
        assertFalse(r.covers(FROM.minusSeconds(1)));
        r.deactivate();
        assertFalse(r.covers(FROM), "inactive rate never covers");
    }

    @Test
    void resolvesDirectAndInvertedConversion() {
        var r = usdToKhr("4100");
        var direct = r.conversionFor(Currency.USD, Currency.KHR);
        assertNotNull(direct);
        assertFalse(direct.inverted());
        assertEquals(0, new BigDecimal("4100").compareTo(direct.rate()));

        var inverted = r.conversionFor(Currency.KHR, Currency.USD);
        assertNotNull(inverted);
        assertTrue(inverted.inverted());

        // Same currency has no conversion factor (identity handled by service).
        assertNull(r.conversionFor(Currency.USD, Currency.USD));
    }

    @Test
    void currencyParseRejectsUnsupported() {
        assertEquals(Currency.USD, Currency.parse("usd"));
        var ex = assertThrows(ValidationException.class, () -> Currency.parse("EUR"));
        assertEquals("UNSUPPORTED_CURRENCY", ex.code());
    }
}
