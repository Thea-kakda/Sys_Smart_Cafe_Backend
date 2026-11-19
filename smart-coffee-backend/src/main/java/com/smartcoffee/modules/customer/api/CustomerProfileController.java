package com.smartcoffee.modules.customer.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.customer.application.CustomerProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers/me")
public class CustomerProfileController {
    private final CustomerProfileService service;
    private final CurrentActorProvider actor;
    public CustomerProfileController(CustomerProfileService service, CurrentActorProvider actor) { this.service = service; this.actor = actor; }

    @GetMapping
    public ApiResponse<CustomerProfileResponse> get() {
        return ApiResponse.ok(service.getByUser(actor.require().userId()), CorrelationContext.requestId());
    }

    @PutMapping
    public ApiResponse<CustomerProfileResponse> update(@Valid @RequestBody UpdateCustomerProfileRequest request) {
        return ApiResponse.ok(service.update(actor.require().userId(), request), CorrelationContext.requestId());
    }
}

