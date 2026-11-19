package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ValidationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Slice 2C §18 — the reservation delta engine in isolation.
 *
 * <p>Every case is expressed with hand-picked ingredient ids so the expected
 * ordering is provable, and the assertions never rely on map iteration order.
 */
class ReservationDeltaEngineTest {

    private static final UUID BEANS = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID MILK = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID OAT = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID SYRUP = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
    private static final UUID ICE = UUID.fromString("eeeeeeee-0000-0000-0000-000000000005");
    private static final UUID CUP12 = UUID.fromString("11111111-0000-0000-0000-000000000006");
    private static final UUID CUP16 = UUID.fromString("22222222-0000-0000-0000-000000000007");

    @Test
    void emptyOldAndEmptyNewProduceNoDelta() {
        assertTrue(ReservationDeltaEngine.delta(Map.of(), Map.of()).isEmpty());
        assertTrue(ReservationDeltaEngine.delta(null, null).isEmpty());
    }

    @Test
    void emptyOldReservesEverythingNew() {
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(Map.of(), Map.of(BEANS, dec("18")));
        assertEquals(1, d.size());
        assertEquals(0, dec("18").compareTo(d.get(BEANS)));
    }

    @Test
    void oldDemandToEmptyReleasesEverything() {
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(Map.of(BEANS, dec("18"), MILK, dec("180")),
                Map.of());
        assertEquals(2, d.size());
        assertEquals(0, dec("-18").compareTo(d.get(BEANS)));
        assertEquals(0, dec("-180").compareTo(d.get(MILK)));
    }

    @Test
    void quantityIncreaseReservesTheDifference() {
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(Map.of(BEANS, dec("18")), Map.of(BEANS, dec("36")));
        assertEquals(0, dec("18").compareTo(d.get(BEANS)));
    }

    @Test
    void quantityDecreaseReleasesTheDifference() {
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(Map.of(BEANS, dec("36")), Map.of(BEANS, dec("18")));
        assertEquals(0, dec("-18").compareTo(d.get(BEANS)));
    }

    @Test
    void sameDemandProducesNoDeltaAndOmitsZeroes() {
        Map<UUID, BigDecimal> demand = Map.of(BEANS, dec("18"), MILK, dec("180"), ICE, dec("120"));
        assertTrue(ReservationDeltaEngine.delta(demand, new HashMap<>(demand)).isEmpty());

        // A three-line move where only one line really changes keeps the two zero
        // comparisons out of the result.
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(demand,
                Map.of(BEANS, dec("18"), MILK, dec("180"), ICE, dec("60")));
        assertEquals(1, d.keySet().size());
        assertEquals(0, dec("-60").compareTo(d.get(ICE)));
    }

    @Test
    void milkReplacementReleasesFreshMilkAndReservesOat() {
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(
                Map.of(BEANS, dec("18"), MILK, dec("180")),
                Map.of(BEANS, dec("18"), OAT, dec("180")));
        assertEquals(List.of(MILK, OAT), new ArrayList<>(d.keySet()), "fresh milk out, oat in");
        assertEquals(0, dec("-180").compareTo(d.get(MILK)));
        assertEquals(0, dec("180").compareTo(d.get(OAT)));
        assertNull(d.get(BEANS), "unchanged beans must not appear");
    }

    @Test
    void sugarReductionReleasesOnlyTheDifference() {
        // Sugar 100% -> 50% of a 20 ml line.
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(Map.of(SYRUP, dec("20")), Map.of(SYRUP, dec("10")));
        assertEquals(1, d.size());
        assertEquals(0, dec("-10").compareTo(d.get(SYRUP)));
    }

    @Test
    void extraShotReservesOnlyTheAddedBeans() {
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(Map.of(BEANS, dec("18")), Map.of(BEANS, dec("27")));
        assertEquals(0, dec("9").compareTo(d.get(BEANS)), "base beans stay held, only +9 g moves");
    }

    @Test
    void variantTwelveToSixteenOuncesSwapsTheCupAndScalesTheRest() {
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(
                Map.of(BEANS, dec("18"), MILK, dec("180"), CUP12, dec("1")),
                Map.of(BEANS, dec("24"), MILK, dec("240"), CUP16, dec("1")));
        assertEquals(0, dec("6").compareTo(d.get(BEANS)));
        assertEquals(0, dec("60").compareTo(d.get(MILK)));
        assertEquals(0, dec("-1").compareTo(d.get(CUP12)));
        assertEquals(0, dec("1").compareTo(d.get(CUP16)));
    }

    @Test
    void multipleIngredientsMoveIndependently() {
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(
                Map.of(BEANS, dec("18"), MILK, dec("180"), SYRUP, dec("20"), ICE, dec("120")),
                Map.of(BEANS, dec("36"), MILK, dec("60"), SYRUP, dec("0.0000"), ICE, dec("120")));
        assertEquals(0, dec("18").compareTo(d.get(BEANS)));
        assertEquals(0, dec("-120").compareTo(d.get(MILK)));
        assertEquals(0, dec("-20").compareTo(d.get(SYRUP)));
        assertNull(d.get(ICE));
    }

    @Test
    void resultIsOrderedByInventoryItemIdRegardlessOfInsertionOrder() {
        Map<UUID, BigDecimal> oldDemand = new LinkedHashMap<>();
        oldDemand.put(ICE, dec("120"));
        oldDemand.put(BEANS, dec("18"));
        oldDemand.put(MILK, dec("180"));
        Map<UUID, BigDecimal> newDemand = new LinkedHashMap<>();
        newDemand.put(MILK, dec("0"));
        newDemand.put(ICE, dec("240"));
        newDemand.put(BEANS, dec("9"));

        List<UUID> keys = new ArrayList<>(ReservationDeltaEngine.delta(oldDemand, newDemand).keySet());
        List<UUID> expected = new ArrayList<>(keys);
        expected.sort((left, right) -> left.compareTo(right));
        assertEquals(expected, keys, "locking order must be deterministic");
        assertEquals(List.of(BEANS, MILK, ICE), keys);
    }

    @Test
    void negativeHeldOrRequestedQuantityIsRejected() {
        ValidationException negativeNew = assertThrows(ValidationException.class,
                () -> ReservationDeltaEngine.delta(Map.of(BEANS, dec("18")), Map.of(BEANS, dec("-1"))));
        assertEquals("INVALID_RESERVATION_DELTA", negativeNew.code());

        ValidationException negativeOld = assertThrows(ValidationException.class,
                () -> ReservationDeltaEngine.delta(Map.of(BEANS, dec("-18")), Map.of(BEANS, dec("1"))));
        assertEquals("INVALID_RESERVATION_DELTA", negativeOld.code());
    }

    @Test
    void inputMapsAreNeverMutated() {
        Map<UUID, BigDecimal> oldDemand = new HashMap<>(Map.of(BEANS, dec("18"), MILK, dec("180")));
        Map<UUID, BigDecimal> newDemand = new HashMap<>(Map.of(BEANS, dec("27"), OAT, dec("180")));
        Map<UUID, BigDecimal> oldCopy = new HashMap<>(oldDemand);
        Map<UUID, BigDecimal> newCopy = new HashMap<>(newDemand);

        ReservationDeltaEngine.delta(oldDemand, newDemand);

        assertEquals(oldCopy, oldDemand);
        assertEquals(newCopy, newDemand);
    }

    @Test
    void decimalPrecisionIsPreserved() {
        SortedMap<UUID, BigDecimal> d = ReservationDeltaEngine.delta(Map.of(SYRUP, dec("20.0000"), MILK, dec("180.125")),
                Map.of(SYRUP, dec("13.3333"), MILK, dec("0.0625")));
        assertEquals(0, dec("-6.6667").compareTo(d.get(SYRUP)));
        assertEquals(new BigDecimal("-6.6667").scale(), d.get(SYRUP).scale(), "no rescaling happens here");
        assertEquals(0, dec("-180.0625").compareTo(d.get(MILK)));
        assertEquals(4, d.get(MILK).scale());
    }

    @Test
    void nullInventoryItemIdIsRejected() {
        // HashMap, not TreeMap: the map itself must be able to carry the null key
        // so the engine, not java.util, is the component that rejects it.
        Map<UUID, BigDecimal> bad = new HashMap<>();
        bad.put(null, dec("1"));
        assertThrows(ValidationException.class, () -> ReservationDeltaEngine.delta(Map.of(), bad));
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}
