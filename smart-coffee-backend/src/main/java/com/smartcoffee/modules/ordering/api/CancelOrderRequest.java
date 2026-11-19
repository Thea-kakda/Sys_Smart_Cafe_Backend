package com.smartcoffee.modules.ordering.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of the administrative cancel command (Full-stack wiring §1.4). A
 * reason is mandatory: cancellations are audited and the reason lands in the
 * status history and the audit trail — there is no anonymous cancel.
 */
public record CancelOrderRequest(@NotBlank @Size(max = 500) String reason) {
}
