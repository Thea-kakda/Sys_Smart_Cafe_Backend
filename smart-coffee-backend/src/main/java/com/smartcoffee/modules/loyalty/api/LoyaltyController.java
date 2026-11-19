package com.smartcoffee.modules.loyalty.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.cart.application.CurrentCustomerResolver;
import com.smartcoffee.modules.loyalty.application.LoyaltyQueryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers/me/loyalty")
public class LoyaltyController {
    private final LoyaltyQueryService service;
    private final CurrentActorProvider actor;
    private final CurrentCustomerResolver customers;

    public LoyaltyController(LoyaltyQueryService s, CurrentActorProvider a, CurrentCustomerResolver c) {
        service = s;
        actor = a;
        customers = c;
    }

    @GetMapping
    public ApiResponse<LoyaltyAccountResponse> get() {
        var user = actor.require().userId();
        return ApiResponse.ok(service.get(customers.customerId(user)), CorrelationContext.requestId());
    }
}

