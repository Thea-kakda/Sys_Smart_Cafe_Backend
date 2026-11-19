package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/management/inventory/reservations/{reservationId}/release}
 * (Slice 2E, spec §12). {@code expectedVersion} is the caller's optimistic-lock
 * token (the reservation version it read); a mismatch is a 409, never a silent
 * overwrite. {@code reason} is mandatory and lands on the ledger + audit.
 * {@code idempotencyKey} may also be supplied via the {@code Idempotency-Key}
 * header; the header wins when both are present.
 */
public record ReservationReleaseRequest(Long expectedVersion, @NotBlank @Size(max = 500) String reason,
        UUID idempotencyKey) {
}
