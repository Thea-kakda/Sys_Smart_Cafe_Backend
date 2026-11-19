package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.request.SetInventoryEffectRequest;
import com.smartcoffee.modules.catalog.application.OptionInventoryEffectCommandService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected upsert/clear of a customization-option's inventory effect. All
 * writes require {@code option-effect:manage}. Version echo is passed as a
 * query parameter (optional; when omitted, the service skips the optimistic
 * lock — the flag flip remains shape-guarded by ck_option_effect_shape).
 */
@Tag(name = "Option inventory effects", description = "Inventory effect configuration for options")
@RestController
@RequestMapping("/api/v1/management/catalog/customization-options")
public class OptionInventoryEffectManagementController {

    private final OptionInventoryEffectCommandService commands;
    private final CurrentActorProvider actors;

    public OptionInventoryEffectManagementController(OptionInventoryEffectCommandService commands,
            CurrentActorProvider actors) {
        this.commands = commands;
        this.actors = actors;
    }

    @PutMapping("/{optionId}/inventory-effect")
    public ApiResponse<InventoryEffectResponse> set(@PathVariable UUID optionId,
            @RequestParam(required = false) Long version,
            @Valid @RequestBody SetInventoryEffectRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(commands.set(actor, optionId, version, req), CorrelationContext.requestId());
    }

    @DeleteMapping("/{optionId}/inventory-effect")
    public ApiResponse<InventoryEffectResponse> clear(@PathVariable UUID optionId,
            @RequestParam(required = false) Long version) {
        var actor = actors.require();
        return ApiResponse.ok(commands.clear(actor, optionId, version), CorrelationContext.requestId());
    }
}
