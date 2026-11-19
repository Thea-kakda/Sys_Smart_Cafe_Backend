package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.infrastructure.idempotency.ApiIdempotencyRecord;
import com.smartcoffee.infrastructure.idempotency.IdempotencyService;
import com.smartcoffee.infrastructure.idempotency.IdempotencyStatus;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.ReservationStatus;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.PaymentSettlement;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2E administrative-release tests (spec §12/§14/§27). The endpoint is the only
 * manual door out of ACTIVE and is fenced on every side: permission, non-empty
 * reason, idempotent replay, terminal-status rules (consumed/converted cannot
 * release; a settled order must consume, never release), the optimistic-lock version
 * guard, store isolation, and — on success — a single guarded release through the
 * shared primitive plus an audit entry.
 */
class AdminReservationReleaseServiceTest {

    private InventoryReservationRepository reservations;
    private InventoryReconciliationQueries queries;
    private InventoryReservationFinalizationService finalization;
    private PermissionEvaluator permissions;
    private StoreAuthorizationService stores;
    private AuditWriter audit;
    private IdempotencyService idempotency;
    private ReservationMetrics metrics;
    private AdminReservationReleaseService service;
    private final ObjectMapper json = JsonMapper.builder().build();

    private UUID userId;
    private UUID store;
    private UUID orderId;
    private UUID resvId;
    private CurrentActor actor;

    @BeforeEach
    void setup() {
        reservations = mock(InventoryReservationRepository.class);
        queries = mock(InventoryReconciliationQueries.class);
        finalization = mock(InventoryReservationFinalizationService.class);
        permissions = mock(PermissionEvaluator.class);
        stores = mock(StoreAuthorizationService.class);
        audit = mock(AuditWriter.class);
        idempotency = mock(IdempotencyService.class);
        metrics = mock(ReservationMetrics.class);
        service = new AdminReservationReleaseService(reservations, queries, finalization, permissions, stores, audit,
                idempotency, json, metrics);

        userId = UUID.randomUUID();
        store = UUID.randomUUID();
        orderId = UUID.randomUUID();
        resvId = UUID.randomUUID();
        actor = new CurrentActor(userId, "STAFF", Set.of(PermissionCodes.RESERVATION_RELEASE), Set.of(store), false);
    }

    private void stubClaimProcessing() {
        ApiIdempotencyRecord record = mock(ApiIdempotencyRecord.class);
        when(record.status()).thenReturn(IdempotencyStatus.PROCESSING);
        when(idempotency.claim(eq("RESERVATION_RELEASE"), any(), any(), any(), any(), any())).thenReturn(record);
    }

    @Test
    void requiresReleasePermission() {
        doThrow(new ForbiddenException("missing")).when(permissions).require(actor, PermissionCodes.RESERVATION_RELEASE);

        assertThrows(ForbiddenException.class,
                () -> service.release(actor, resvId, null, "reason", UUID.randomUUID()));
        verifyNoInteractions(reservations);
    }

    @Test
    void blankReasonIsRejected() {
        assertThrows(ValidationException.class, () -> service.release(actor, resvId, null, "  ", UUID.randomUUID()));
        verifyNoInteractions(reservations);
    }

    @Test
    void completedIdempotencyKeyReplaysWithoutLocking() throws Exception {
        AdminReservationReleaseService.ReleaseResult prior =
                new AdminReservationReleaseService.ReleaseResult(resvId, store, "RELEASED", true, 3L);
        ApiIdempotencyRecord record = mock(ApiIdempotencyRecord.class);
        when(record.status()).thenReturn(IdempotencyStatus.COMPLETED);
        when(record.responseBody()).thenReturn(json.writeValueAsString(prior));
        when(idempotency.claim(any(), any(), any(), any(), any(), any())).thenReturn(record);

        AdminReservationReleaseService.ReleaseResult out = service.release(actor, resvId, null, "reason",
                UUID.randomUUID());

        assertEquals(prior, out);
        verify(reservations, never()).lock(any());
    }

    @Test
    void missingReservationIsNotFound() {
        stubClaimProcessing();
        when(reservations.lock(resvId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.release(actor, resvId, null, "reason", UUID.randomUUID()));
    }

    @Test
    void consumedReservationCannotBeReleased() {
        stubClaimProcessing();
        InventoryReservation consumed = orderHold();
        consumed.consumed(userId);
        when(reservations.lock(any())).thenReturn(Optional.of(consumed));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.release(actor, consumed.id(), null, "reason", UUID.randomUUID()));
        assertEquals("RESERVATION_CONSUMED_CANNOT_RELEASE", ex.code());
        verifyNoInteractions(finalization);
    }

    @Test
    void convertedReservationCannotBeReleasedIndependently() {
        stubClaimProcessing();
        InventoryReservation converted = mock(InventoryReservation.class);
        when(converted.status()).thenReturn(ReservationStatus.CONVERTED);
        when(converted.storeId()).thenReturn(store);
        when(reservations.lock(any())).thenReturn(Optional.of(converted));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.release(actor, resvId, null, "reason", UUID.randomUUID()));
        assertEquals("RESERVATION_CONVERTED_CANNOT_RELEASE", ex.code());
    }

    @Test
    void alreadyTerminalStatusIsAStableNoOp() {
        stubClaimProcessing();
        InventoryReservation expired = mock(InventoryReservation.class);
        when(expired.status()).thenReturn(ReservationStatus.EXPIRED);
        when(expired.id()).thenReturn(resvId);
        when(expired.storeId()).thenReturn(store);
        when(expired.version()).thenReturn(2L);
        when(reservations.lock(any())).thenReturn(Optional.of(expired));

        AdminReservationReleaseService.ReleaseResult out = service.release(actor, resvId, null, "reason",
                UUID.randomUUID());

        assertFalse(out.released());
        assertEquals("EXPIRED", out.status());
        verifyNoInteractions(finalization);
    }

    @Test
    void versionMismatchIsAConflict() {
        stubClaimProcessing();
        InventoryReservation held = orderHold(); // version 0
        when(reservations.lock(held.id())).thenReturn(Optional.of(held));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.release(actor, held.id(), 7L, "reason", UUID.randomUUID()));
        assertEquals("RESERVATION_VERSION_CONFLICT", ex.code());
        verifyNoInteractions(finalization);
    }

    @Test
    void settledOrderReservationCannotBeReleased() {
        stubClaimProcessing();
        InventoryReservation held = orderHold();
        when(reservations.lock(held.id())).thenReturn(Optional.of(held));
        when(queries.paymentSettlement(orderId)).thenReturn(PaymentSettlement.SETTLED);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.release(actor, held.id(), null, "reason", UUID.randomUUID()));
        assertEquals("RESERVATION_SETTLED_CANNOT_RELEASE", ex.code());
        verifyNoInteractions(finalization);
    }

    @Test
    void crossStoreAccessIsDeniedBeforeAnyMutation() {
        stubClaimProcessing();
        InventoryReservation held = orderHold();
        when(reservations.lock(held.id())).thenReturn(Optional.of(held));
        doThrow(new ForbiddenException("STORE_ACCESS_DENIED", "denied")).when(stores).requireStore(actor, store);

        assertThrows(ForbiddenException.class,
                () -> service.release(actor, held.id(), null, "reason", UUID.randomUUID()));
        verifyNoInteractions(finalization);
    }

    @Test
    void activeUnsettledOrderReleasesOnceThroughTheSharedPrimitive() {
        stubClaimProcessing();
        InventoryReservation held = orderHold();
        when(reservations.lock(held.id())).thenReturn(Optional.of(held));
        when(queries.paymentSettlement(orderId)).thenReturn(PaymentSettlement.NOT_SETTLED);
        when(finalization.releaseReservation(eq(held.id()), eq("reason"), eq(userId))).thenReturn(true);

        AdminReservationReleaseService.ReleaseResult out = service.release(actor, held.id(), null, "reason",
                UUID.randomUUID());

        assertTrue(out.released());
        assertEquals("RELEASED", out.status());
        verify(finalization).releaseReservation(held.id(), "reason", userId);
        verify(audit).success(eq(userId), eq(store), eq("INVENTORY_RESERVATION_ADMIN_RELEASE"), any(), any(),
                eq(held.id()), any(), any(), eq("reason"));
        verify(metrics).increment(eq(ReservationMetrics.ADMIN_RELEASE), any(String[].class));
    }

    // ------------------------------------------------------------------ fixtures

    private InventoryReservation orderHold() {
        return InventoryReservation.fromCartHolds(store, orderId, UUID.randomUUID(),
                Instant.now().plusSeconds(600), Map.of(UUID.randomUUID(), java.math.BigDecimal.TEN), Map.of(), "key");
    }
}
