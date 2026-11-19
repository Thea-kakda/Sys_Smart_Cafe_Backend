package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.modules.inventory.application.InventoryReservationFinalizationService.LegacyLine;
import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.domain.InventoryBalanceId;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.ReservationStatus;
import com.smartcoffee.modules.inventory.domain.StockTransaction;
import com.smartcoffee.modules.inventory.domain.StockTransactionType;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Slice 2D unit tests for reservation finalization: the ownership conversion at
 * checkout, the exactly-once consumption at settlement, and the exactly-once
 * release on cancellation.
 *
 * <p>Real aggregates are used (only the persistence layer is mocked) so the
 * assertions describe the actual domain transitions instead of mock choreography:
 * the conversion must not move a balance or write a ledger row, the consumption
 * must draw both balances by the snapshot quantity, the release must touch only
 * the held quantity, and a replay of either terminal transition must be a no-op.
 */
class InventoryReservationFinalizationServiceTest {

    private InventoryBalanceRepository balances;
    private InventoryReservationRepository reservations;
    private StockTransactionRepository ledger;
    private IngredientRepository ingredients;
    private FefoConsumptionService fefo;
    private InventoryReservationFinalizationService service;

    private UUID store;
    private UUID cart;
    private UUID cartItem;
    private UUID order;
    private UUID orderLine;
    private UUID beans;
    private UUID milk;
    private UUID actor;

    @BeforeEach
    void setup() {
        balances = mock(InventoryBalanceRepository.class);
        reservations = mock(InventoryReservationRepository.class);
        ledger = mock(StockTransactionRepository.class);
        ingredients = mock(IngredientRepository.class);
        fefo = mock(FefoConsumptionService.class);
        service = new InventoryReservationFinalizationService(balances, reservations, ledger, ingredients, fefo);

        store = UUID.randomUUID();
        cart = UUID.randomUUID();
        cartItem = UUID.randomUUID();
        order = UUID.randomUUID();
        orderLine = UUID.randomUUID();
        beans = UUID.randomUUID();
        milk = UUID.randomUUID();
        actor = UUID.randomUUID();

        when(reservations.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(reservations.findByOrderId(any())).thenReturn(Optional.empty());
        when(ingredients.findById(any())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------- conversion (§4/§5)

    @Test
    void conversionTransfersOwnershipWithoutMovingAnyBalance() {
        InventoryReservation hold = cartHold(Map.of(beans, "27"), Map.of(beans, "G"));
        when(reservations.lockActiveByCartId(cart)).thenReturn(List.of(hold));

        Optional<UUID> reservationId = service.convert(store, cart, order, expiry(),
                Map.of(cartItem, orderLine), List.of(), actor, "key");

        InventoryReservation saved = capturedReservation();
        assertEquals(reservationId.orElseThrow(), saved.id());
        assertEquals("ORDER", saved.sourceType());
        assertEquals(order, saved.orderId());
        assertEquals(cart, saved.cartId(), "the cart stays as provenance on the order hold");
        assertEquals(ReservationStatus.ACTIVE, saved.status());
        assertEquals(0, new BigDecimal("27").compareTo(saved.demandSnapshot().get(beans)),
                "the persisted cart snapshot is transferred unchanged");
        assertEquals("G", saved.lines().get(0).baseUnitCode());

        // The cart row is kept, marked CONVERTED and points at the order hold.
        assertEquals(ReservationStatus.CONVERTED, hold.status());
        assertEquals(saved.id(), hold.convertedToReservationId());
        assertEquals(order, hold.convertedOrderId());
        assertEquals(orderLine, hold.convertedOrderLineId());
        assertNotNull(hold.convertedAt());
        assertEquals(0, new BigDecimal("27").compareTo(hold.demandSnapshot().get(beans)),
                "the converted cart row keeps its own snapshot for audit");

        // No-release-gap: nothing was reserved, released or consumed.
        verifyNoInteractions(balances);
        verifyNoInteractions(ledger);
    }

    @Test
    void conversionReservesOnlyTheDemandNoCartLineHeld() {
        InventoryReservation hold = cartHold(Map.of(beans, "18"), Map.of(beans, "G"));
        when(reservations.lockActiveByCartId(cart)).thenReturn(List.of(hold));
        when(balances.findById(any())).thenReturn(Optional.of(balance("0")));
        when(balances.reserve(any(), any(), any())).thenReturn(1);

        ReservationDemand legacy = new ReservationDemand(Map.of(milk, new BigDecimal("180")),
                Map.of(milk, "ML"), null, null, "NONE");
        service.convert(store, cart, order, expiry(), Map.of(cartItem, orderLine),
                List.of(new LegacyLine(cartItem, orderLine, legacy)), actor, "key");

        InventoryReservation saved = capturedReservation();
        assertEquals(2, saved.lines().size(), "held and legacy demand are aggregated");
        assertEquals(0, new BigDecimal("18").compareTo(saved.demandSnapshot().get(beans)));
        assertEquals(0, new BigDecimal("180").compareTo(saved.demandSnapshot().get(milk)));

        // Only the legacy part is newly reserved; the held part must not move twice.
        verify(balances).reserve(store, milk, new BigDecimal("180"));
        verify(balances, never()).reserve(eq(store), eq(beans), any());

        StockTransaction row = capturedLedger();
        assertEquals(StockTransactionType.SALE_RESERVATION, row.type());
        assertEquals(ReservationReasons.LEGACY_CHECKOUT_RESERVE, row.reason());
    }

    @Test
    void conversionOfNothingHeldCreatesNoReservation() {
        when(reservations.lockActiveByCartId(cart)).thenReturn(List.of());

        assertTrue(service.convert(store, cart, order, expiry(), Map.of(cartItem, orderLine),
                List.of(new LegacyLine(cartItem, orderLine, ReservationDemand.empty())), actor, "key").isEmpty());

        verify(reservations, never()).saveAndFlush(any());
        verifyNoInteractions(balances, ledger);
    }

    @Test
    void anOrderCannotAcquireASecondReservation() {
        when(reservations.findByOrderId(order)).thenReturn(Optional.of(cartHold(Map.of(beans, "1"), Map.of())));

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.convert(store, cart, order, expiry(), Map.of(cartItem, orderLine), List.of(), actor, "k"));

        assertEquals("RESERVATION_ALREADY_CONVERTED", conflict.code());
        verify(reservations, never()).saveAndFlush(any());
    }

    @Test
    void aHoldOutsideTheCheckedOutLinesIsRejected() {
        when(reservations.lockActiveByCartId(cart))
                .thenReturn(List.of(cartHold(Map.of(beans, "1"), Map.of())));

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.convert(store, cart, order, expiry(), Map.of(UUID.randomUUID(), orderLine),
                        List.of(), actor, "k"));

        assertEquals("CART_RESERVATION_CONFLICT", conflict.code());
    }

    @Test
    void aHoldOfAnotherStoreIsRejected() {
        InventoryReservation foreign = cartHold(Map.of(beans, "1"), Map.of());
        when(reservations.lockActiveByCartId(cart)).thenReturn(List.of(foreign));

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.convert(UUID.randomUUID(), cart, order, expiry(), Map.of(cartItem, orderLine),
                        List.of(), actor, "k"));

        assertEquals("CART_RESERVATION_CONFLICT", conflict.code());
    }

    @Test
    void conversionFailsWhenTheLegacyDemandCannotBeReserved() {
        when(reservations.lockActiveByCartId(cart)).thenReturn(List.of());
        when(balances.findById(any())).thenReturn(Optional.of(balance("0")));
        when(balances.reserve(any(), any(), any())).thenReturn(0);
        ReservationDemand legacy = new ReservationDemand(Map.of(milk, new BigDecimal("500")),
                Map.of(milk, "ML"), null, null, "NONE");

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.convert(store, cart, order, expiry(), Map.of(cartItem, orderLine),
                        List.of(new LegacyLine(cartItem, orderLine, legacy)), actor, "k"));

        assertEquals("INSUFFICIENT_STOCK", conflict.code());
        verifyNoInteractions(ledger);
    }

    // ------------------------------------------------------ consumption (§10/§11)

    @Test
    void consumptionDrawsTheSnapshotDownAndWritesOneLedgerTransaction() {
        InventoryReservation held = orderHold(Map.of(beans, "27", milk, "180"));
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));
        when(balances.findById(any())).thenReturn(Optional.of(balance("1000")));
        when(balances.consume(any(), any(), any())).thenReturn(1);

        assertTrue(service.consumeOrder(order, actor));

        verify(balances).consume(store, beans, new BigDecimal("27"));
        verify(balances).consume(store, milk, new BigDecimal("180"));
        assertEquals(ReservationStatus.CONSUMED, held.status());
        assertEquals(actor, held.consumedBy());
        assertEquals(0, new BigDecimal("27").compareTo(held.demandSnapshot().get(beans)),
                "consumption must not rewrite the snapshot");

        StockTransaction row = capturedLedger();
        assertEquals(StockTransactionType.SALE_CONSUMPTION, row.type());
        assertEquals(ReservationReasons.ORDER_SETTLED, row.reason());
        verify(ledger, times(1)).save(any());
    }

    @Test
    void consumptionVisitsInventoryItemsInAscendingIdOrder() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID second = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        InventoryReservation held = orderHold(Map.of(second, "1", first, "2"));
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));
        when(balances.findById(any())).thenReturn(Optional.of(balance("1000")));
        when(balances.consume(any(), any(), any())).thenReturn(1);

        service.consumeOrder(order, actor);

        var order = inOrder(balances);
        order.verify(balances).consume(store, first, new BigDecimal("2"));
        order.verify(balances).consume(store, second, new BigDecimal("1"));
    }

    @Test
    void consumptionReplayIsANoOp() {
        InventoryReservation held = orderHold(Map.of(beans, "27"));
        held.consumed(actor);
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));

        assertFalse(service.consumeOrder(order, actor));

        verifyNoInteractions(balances);
        verifyNoInteractions(ledger);
    }

    @Test
    void consumptionOfAnOrderThatHoldsNothingIsANoOp() {
        assertFalse(service.consumeOrder(order, actor));

        verifyNoInteractions(balances, ledger);
    }

    @Test
    void aReleasedReservationCannotBeConsumed() {
        InventoryReservation held = orderHold(Map.of(beans, "27"));
        held.released(ReservationReasons.ORDER_CANCELLED);
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.consumeOrder(order, actor));

        assertEquals("RESERVATION_NOT_ACTIVE", conflict.code());
        verifyNoInteractions(balances, ledger);
    }

    @Test
    void aBalanceConflictAbortsTheWholeConsumption() {
        InventoryReservation held = orderHold(Map.of(beans, "27", milk, "180"));
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));
        when(balances.findById(any())).thenReturn(Optional.of(balance("1000")));
        when(balances.consume(any(), eq(beans), any())).thenReturn(1);
        when(balances.consume(any(), eq(milk), any())).thenReturn(0);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.consumeOrder(order, actor));

        assertEquals("RESERVATION_BALANCE_CONFLICT", conflict.code());
        assertEquals(ReservationStatus.ACTIVE, held.status(), "a failed consumption leaves the hold ACTIVE");
        verify(ledger, never()).save(any());
    }

    // ------------------------------------------------------------ release (§14)

    @Test
    void cancellationReleasesTheHeldQuantityOnly() {
        InventoryReservation held = orderHold(Map.of(beans, "27", milk, "180"));
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));
        when(balances.findById(any())).thenReturn(Optional.of(balance("1000")));
        when(balances.release(any(), any(), any())).thenReturn(1);

        assertTrue(service.releaseOrder(order, ReservationReasons.ORDER_CANCELLED, actor));

        verify(balances).release(store, beans, new BigDecimal("27"));
        verify(balances).release(store, milk, new BigDecimal("180"));
        verify(balances, never()).consume(any(), any(), any());
        assertEquals(ReservationStatus.RELEASED, held.status());
        assertEquals(ReservationReasons.ORDER_CANCELLED, held.releaseReason());

        StockTransaction row = capturedLedger();
        assertEquals(StockTransactionType.SALE_RELEASE, row.type());
        assertEquals(ReservationReasons.ORDER_CANCELLED, row.reason());
    }

    @Test
    void aRepeatedCancellationDoesNotReleaseTwice() {
        InventoryReservation held = orderHold(Map.of(beans, "27"));
        held.released(ReservationReasons.ORDER_CANCELLED);
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));

        assertFalse(service.releaseOrder(order, ReservationReasons.ORDER_CANCELLED, actor));

        verifyNoInteractions(balances);
        verifyNoInteractions(ledger);
    }

    @Test
    void consumedStockCanNeverBeReleased() {
        InventoryReservation held = orderHold(Map.of(beans, "27"));
        held.consumed(actor);
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));

        assertFalse(service.releaseOrder(order, ReservationReasons.ORDER_CANCELLED, actor));

        verify(balances, never()).release(any(), any(), any());
        verifyNoInteractions(ledger);
    }

    @Test
    void aReleaseBalanceConflictIsReportedAsAConflict() {
        InventoryReservation held = orderHold(Map.of(beans, "27"));
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));
        when(balances.findById(any())).thenReturn(Optional.of(balance("1")));
        when(balances.release(any(), any(), any())).thenReturn(0);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.releaseOrder(order, ReservationReasons.PAYMENT_FAILED, actor));

        assertEquals("RESERVATION_BALANCE_CONFLICT", conflict.code());
        assertEquals(ReservationStatus.ACTIVE, held.status());
    }

    // -------------------------------------------------------------- fixtures

    private InventoryReservation cartHold(Map<UUID, String> demand, Map<UUID, String> units) {
        return InventoryReservation.forCart(store, cart, cartItem, expiry(), quantities(demand), units, null, null,
                "VARIANT", "key");
    }

    private InventoryReservation orderHold(Map<UUID, String> demand) {
        return InventoryReservation.fromCartHolds(store, order, cart, expiry(), quantities(demand), Map.of(), "key");
    }

    private static Map<UUID, BigDecimal> quantities(Map<UUID, String> demand) {
        Map<UUID, BigDecimal> quantities = new java.util.LinkedHashMap<>();
        demand.forEach((item, quantity) -> quantities.put(item, new BigDecimal(quantity)));
        return quantities;
    }

    private static InventoryBalance balance(String current) {
        return InventoryBalance.open(new InventoryBalanceId(UUID.randomUUID(), UUID.randomUUID()),
                new BigDecimal(current), BigDecimal.ZERO);
    }

    private static Instant expiry() {
        return Instant.now().plusSeconds(900);
    }

    private InventoryReservation capturedReservation() {
        ArgumentCaptor<InventoryReservation> captor = ArgumentCaptor.forClass(InventoryReservation.class);
        verify(reservations).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private StockTransaction capturedLedger() {
        ArgumentCaptor<StockTransaction> captor = ArgumentCaptor.forClass(StockTransaction.class);
        verify(ledger).save(captor.capture());
        return captor.getValue();
    }
}
