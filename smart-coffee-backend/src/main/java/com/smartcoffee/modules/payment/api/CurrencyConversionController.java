package com.smartcoffee.modules.payment.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.payment.application.CurrencyConversionService;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only currency conversion preview (Checkpoint 5B). POST is used because
 * the request carries a body (branch, currencies, amount) and the operation is
 * a parameterized calculation, matching the repository's command-style API. It
 * never mutates cart/order/payment/receipt state and reserves no rate.
 */
@RestController
@RequestMapping("/api/v1/currency-conversions")
public class CurrencyConversionController {

    private final CurrencyConversionService conversions;
    private final CurrentActorProvider actors;

    public CurrencyConversionController(CurrencyConversionService conversions, CurrentActorProvider actors) {
        this.conversions = conversions;
        this.actors = actors;
    }

    @PostMapping("/preview")
    public ApiResponse<CurrencyConversionResponse> preview(
            @Valid @RequestBody CurrencyConversionPreviewRequest request) {
        var actor = actors.require();
        var result = conversions.preview(actor, request.storeId(), request.sourceCurrency(), request.targetCurrency(),
                request.amount());
        return ApiResponse.ok(CurrencyConversionResponse.from(result, Instant.now()),
                CorrelationContext.requestId());
    }
}
