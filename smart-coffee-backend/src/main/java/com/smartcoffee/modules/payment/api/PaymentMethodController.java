package com.smartcoffee.modules.payment.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.modules.payment.persistence.PaymentMethodRepository;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payment-methods")
public class PaymentMethodController {
    private final PaymentMethodRepository repo;

    public PaymentMethodController(PaymentMethodRepository r) {
        repo = r;
    }

    @GetMapping
    public ApiResponse<List<PaymentMethodResponse>> list() {
        return ApiResponse.ok(
                repo.findByActiveTrueOrderByDisplayOrderAsc().stream()
                        .map(x -> new PaymentMethodResponse(x.id(), x.code(), x.name(), x.type().name())).toList(),
                CorrelationContext.requestId());
    }
}

