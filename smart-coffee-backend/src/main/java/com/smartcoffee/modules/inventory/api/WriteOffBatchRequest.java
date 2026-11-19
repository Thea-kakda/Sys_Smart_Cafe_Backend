package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Manual write-off of a batch/lot (V038, M3): a waste/expiry stock-out that
 * retires one ACTIVE lot, draws its remaining quantity out of the aggregate
 * balance through the guarded native statement, and stamps one DAMAGED ledger
 * line carrying the batch id. The mandatory {@code reason} is recorded on the
 * ledger transaction and the audit entry, exactly as for a manual adjustment.
 */
public record WriteOffBatchRequest(
        @NotBlank @Size(max = 500) String reason) {
}
