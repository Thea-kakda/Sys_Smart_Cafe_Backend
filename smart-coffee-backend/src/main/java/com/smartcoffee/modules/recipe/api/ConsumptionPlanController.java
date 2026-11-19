package com.smartcoffee.modules.recipe.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.recipe.api.request.ConsumptionPlanPreviewRequest;
import com.smartcoffee.modules.recipe.application.ConsumptionPlanMapper;
import com.smartcoffee.modules.recipe.application.ConsumptionPlanService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management-only consumption-plan preview (Slice 2B). Computes a deterministic,
 * READ-ONLY ingredient demand plan for one configured item — it never reserves or
 * consumes stock, and is intentionally kept out of the public Customer / POS APIs.
 *
 * <p>Authorization ({@code recipe:view}) is enforced inside
 * {@link ConsumptionPlanService}; this controller only resolves the current actor and
 * wraps the result in the standard envelope. No domain calculation is orchestrated
 * here.
 */
@Tag(name = "Consumption Plans", description = "Read-only ingredient consumption planning preview")
@RestController
@RequestMapping("/api/v1/management/catalog")
public class ConsumptionPlanController {

    private final ConsumptionPlanService service;
    private final ConsumptionPlanMapper mapper;
    private final CurrentActorProvider actors;

    public ConsumptionPlanController(ConsumptionPlanService service, ConsumptionPlanMapper mapper,
            CurrentActorProvider actors) {
        this.service = service;
        this.mapper = mapper;
        this.actors = actors;
    }

    @PostMapping("/consumption-plans/preview")
    public ApiResponse<ConsumptionPlanPreviewResponse> preview(
            @Valid @RequestBody ConsumptionPlanPreviewRequest req) {
        var actor = actors.require();
        var plan = service.preview(actor, req.productId(), req.variantId(),
                req.productQuantity(), req.selectedOptions());
        return ApiResponse.ok(mapper.toResponse(plan), CorrelationContext.requestId());
    }
}
