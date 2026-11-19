package com.smartcoffee.modules.payment.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.payment.application.PaymentApplicationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentApplicationService service;
    private final CurrentActorProvider actor;

    public PaymentController(PaymentApplicationService s, CurrentActorProvider a) {
        service = s;
        actor = a;
    }

    @PostMapping("/cash")
    public ApiResponse<PaymentResponse> cash(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody CashPaymentRequest req) {
        return ApiResponse.ok(service.cash(actor.require().userId(), key, req), CorrelationContext.requestId());
    }

    @PostMapping("/khqr")
    public ApiResponse<PaymentResponse> khqr(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody KhqrPaymentRequest req) {
        return ApiResponse.ok(service.createKhqr(actor.require().userId(), key, req), CorrelationContext.requestId());
    }

    @PostMapping("/{paymentId}/verify")
    public ApiResponse<PaymentResponse> verify(@PathVariable UUID paymentId) {
        return ApiResponse.ok(service.verifyKhqr(actor.require().userId(), paymentId), CorrelationContext.requestId());
    }
}

