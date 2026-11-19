package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import tools.jackson.databind.ObjectMapper;
import com.smartcoffee.infrastructure.idempotency.ApiIdempotencyRecord;
import com.smartcoffee.infrastructure.idempotency.IdempotencyService;
import com.smartcoffee.infrastructure.idempotency.IdempotencyStatus;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.ReservationStatus;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.PaymentSettlement;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Safe administrative release of one ACTIVE hold (Slice 2E, spec §12). This is the
 * ONLY way a reservation leaves {@code ACTIVE} outside the automatic workers, and
 * it is heavily fenced:
 *
 * <ul>
 *   <li>requires {@code reservation:release} and the reservation's store scope
 *       (enforced in the service, not just the controller — spec §14);</li>
 *   <li>only {@code ACTIVE} holds release. A {@code CONSUMED} hold can never be
 *       released; a {@code CONVERTED} cart hold cannot be released independently
 *       (its target owns the stock); an already terminal hold is an idempotent
 *       no-op;</li>
 *   <li>an order hold whose payment is provably settled is refused — that is the
 *       paid-but-unconsumed path, which must consume, never release;</li>
 *   <li>{@code expectedVersion} guards the optimistic-lock token so a concurrent
 *       settlement/cancellation wins instead of being silently overwritten;</li>
 *   <li>the release itself runs through
 *       {@link InventoryReservationFinalizationService#releaseReservation} — the
 *       same guarded primitive — so there is never a second release algorithm; and</li>
 *   <li>a non-empty {@code reason} is mandatory and an audit entry is written.</li>
 * </ul>
 */
@Service
public class AdminReservationReleaseService {

    private static final Logger log = LoggerFactory.getLogger(AdminReservationReleaseService.class);

    private final InventoryReservationRepository reservations;
    private final InventoryReconciliationQueries queries;
    private final InventoryReservationFinalizationService finalization;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;
    private final AuditWriter audit;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final ReservationMetrics metrics;

    public AdminReservationReleaseService(InventoryReservationRepository reservations,
            InventoryReconciliationQueries queries, InventoryReservationFinalizationService finalization,
            PermissionEvaluator permissions, StoreAuthorizationService stores, AuditWriter audit,
            IdempotencyService idempotency, ObjectMapper json, ReservationMetrics metrics) {
        this.reservations = reservations;
        this.queries = queries;
        this.finalization = finalization;
        this.permissions = permissions;
        this.stores = stores;
        this.audit = audit;
        this.idempotency = idempotency;
        this.json = json;
        this.metrics = metrics;
    }

    /** Result of an administrative release. */
    public record ReleaseResult(UUID reservationId, UUID storeId, String status, boolean released, long version) {
    }

    @Transactional
    public ReleaseResult release(CurrentActor actor, UUID reservationId, Long expectedVersion, String reason,
            UUID idempotencyKey) {
        permissions.require(actor, PermissionCodes.RESERVATION_RELEASE);
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("RELEASE_REASON_REQUIRED", "An administrative release requires a reason");
        }
        String hash = canonicalHash(reservationId, expectedVersion, reason);
        ApiIdempotencyRecord claim = idempotency.claim("RESERVATION_RELEASE", actor.actorType(), actor.userId(),
                idempotencyKey, hash, Duration.ofDays(7));
        if (claim.status() == IdempotencyStatus.COMPLETED) {
            return replay(claim);
        }

        InventoryReservation r = reservations.lock(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("RESERVATION_NOT_FOUND", "Reservation does not exist"));
        stores.requireStore(actor, r.storeId());

        ReleaseResult result = switch (r.status()) {
            case ACTIVE -> releaseActive(r, expectedVersion, reason, actor);
            case CONSUMED -> throw new ConflictException("RESERVATION_CONSUMED_CANNOT_RELEASE",
                    "A consumed reservation cannot be released");
            case CONVERTED -> throw new ConflictException("RESERVATION_CONVERTED_CANNOT_RELEASE",
                    "A converted cart hold cannot be released independently; its target owns the stock");
            // RELEASED / EXPIRED / CANCELLED: already terminal — a stable idempotent no-op.
            default -> new ReleaseResult(r.id(), r.storeId(), r.status().name(), false, r.version());
        };

        metrics.increment(ReservationMetrics.ADMIN_RELEASE,
                "released", String.valueOf(result.released()));
        writeAudit(actor, r, reason, result);
        complete(claim, result);
        return result;
    }

    private ReleaseResult releaseActive(InventoryReservation r, Long expectedVersion, String reason,
            CurrentActor actor) {
        if (expectedVersion != null && expectedVersion != r.version()) {
            throw new ConflictException("RESERVATION_VERSION_CONFLICT",
                    "Reservation changed concurrently (expected " + expectedVersion + ", actual " + r.version() + ")");
        }
        // A settled order's hold must never be administratively released (§4B/§12).
        if ("ORDER".equals(r.sourceType()) && r.orderId() != null
                && queries.paymentSettlement(r.orderId()) == PaymentSettlement.SETTLED) {
            throw new ConflictException("RESERVATION_SETTLED_CANNOT_RELEASE",
                    "An order reservation with a settled payment cannot be released");
        }
        boolean moved = finalization.releaseReservation(r.id(), reason, actor.userId());
        return new ReleaseResult(r.id(), r.storeId(), ReservationStatus.RELEASED.name(), moved, r.version() + (moved ? 1 : 0));
    }

    private void writeAudit(CurrentActor actor, InventoryReservation before, String reason, ReleaseResult after) {
        audit.success(actor.userId(), before.storeId(), "INVENTORY_RESERVATION_ADMIN_RELEASE", "INVENTORY",
                "INVENTORY_RESERVATION", before.id(), before.status().name(), after.status(), reason);
    }

    private ReleaseResult replay(ApiIdempotencyRecord claim) {
        try {
            return json.readValue(claim.responseBody(), ReleaseResult.class);
        } catch (Exception e) {
            throw new ConflictException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot replay release response");
        }
    }

    private void complete(ApiIdempotencyRecord claim, ReleaseResult result) {
        try {
            claim.complete(200, json.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("could not store release idempotency response reservationId={}", result.reservationId(), e);
        }
    }

    /**
     * Deterministic 64-char request identity (the {@code request_hash} column is
     * {@code varchar(64)}). A repeated call with a different reservation/version/
     * reason under the same key is a genuine reuse and must be rejected.
     */
    private static String canonicalHash(UUID reservationId, Long expectedVersion, String reason) {
        String raw = "RESERVATION_RELEASE|" + reservationId + "|" + expectedVersion + "|" + reason;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
