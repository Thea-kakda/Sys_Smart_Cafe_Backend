package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/management/inventory/reconciliation/{runId}/repair}
 * (Slice 2E, spec §13). The operator must confirm {@code dryRun=false} and supply a
 * non-empty reason; the endpoint is additionally gated on the
 * {@code reconciliation:repair} permission and re-validates every finding live.
 */
public record RepairRequest(Boolean dryRun, @NotBlank @Size(max = 500) String reason, UUID idempotencyKey) {
}
