package com.smartcoffee.modules.promotion.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.promotion.application.PromotionEligibilityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionController {
    private final PromotionEligibilityService service;
    private final CurrentActorProvider actor;
    private final PermissionEvaluator permissions;

    public PromotionController(PromotionEligibilityService s, CurrentActorProvider a, PermissionEvaluator p) {
        service = s;
        actor = a;
        permissions = p;
    }

    /**
     * Management diagnostic only: the payload is client-trusting (caller-supplied
     * subtotal, customer and product/category ids), so it requires
     * {@code promotion:read} and must not be used as the customer contract.
     * Customers quote against their own server-owned cart at
     * {@code POST /api/v1/carts/current/promotion/quote} instead.
     *
     * @deprecated use the cart-owned promotion quote endpoint for customer flows
     */
    @Deprecated
    @PostMapping("/validate")
    public ApiResponse<PromotionResponse> validate(@Valid @RequestBody PromotionValidationRequest q) {
        permissions.require(actor.require(), PermissionCodes.PROMOTION_READ);
        return ApiResponse.ok(service.validate(q), CorrelationContext.requestId());
    }
}
