package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.request.CreateCustomizationGroupRequest;
import com.smartcoffee.modules.catalog.api.request.CreateCustomizationOptionRequest;
import com.smartcoffee.modules.catalog.api.request.ReorderOptionsRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCustomizationGroupRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCustomizationOptionRequest;
import com.smartcoffee.modules.catalog.application.CustomizationMasterManagementService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Master customization group/option management (Slice 4A Round 2, §7). All
 * routes require {@code product:manage} — the same gate as the surrounding
 * catalog editor and the product attachment surface, so one grant covers the
 * whole customization configuration and no new permission seed migration is
 * needed.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/v1/management/catalog/customization-groups</li>
 *   <li>POST /api/v1/management/catalog/customization-groups</li>
 *   <li>PATCH  /api/v1/management/catalog/customization-groups/{groupId}</li>
 *   <li>PUT  /api/v1/management/catalog/customization-groups/{groupId}/activate</li>
 *   <li>PUT  /api/v1/management/catalog/customization-groups/{groupId}/deactivate</li>
 *   <li>POST  /api/v1/management/catalog/customization-groups/{groupId}/options</li>
 *   <li>POST  /api/v1/management/catalog/customization-groups/{groupId}/options/reorder</li>
 *   <li>PATCH  /api/v1/management/catalog/customization-options/{optionId}</li>
 *   <li>PUT  /api/v1/management/catalog/customization-options/{optionId}/activate</li>
 *   <li>PUT  /api/v1/management/catalog/customization-options/{optionId}/deactivate</li>
 * </ul>
 *
 * <p>The inventory effect keeps its own route
 * ({@code /customization-options/{id}/inventory-effect}) in
 * {@link OptionInventoryEffectManagementController}; product-specific
 * overrides keep theirs under {@code /products/{id}/customization-groups}.
 */
@Tag(name = "Customization masters", description = "Customization group and option master management")
@RestController
@RequestMapping("/api/v1/management/catalog")
public class CustomizationMasterManagementController {

    private final CustomizationMasterManagementService service;
    private final CurrentActorProvider actors;

    public CustomizationMasterManagementController(CustomizationMasterManagementService service,
            CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/customization-groups")
    public ApiResponse<List<CustomizationGroupMasterResponse>> list() {
        var actor = actors.require();
        return ApiResponse.ok(service.list(actor), CorrelationContext.requestId());
    }

    @PostMapping("/customization-groups")
    public ApiResponse<CustomizationGroupMasterResponse> createGroup(
            @Valid @RequestBody CreateCustomizationGroupRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(service.createGroup(actor, req), CorrelationContext.requestId());
    }

    @PatchMapping("/customization-groups/{groupId}")
    public ApiResponse<CustomizationGroupMasterResponse> updateGroup(@PathVariable UUID groupId,
            @Valid @RequestBody UpdateCustomizationGroupRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(service.updateGroup(actor, groupId, req), CorrelationContext.requestId());
    }

    @PutMapping("/customization-groups/{groupId}/activate")
    public ApiResponse<CustomizationGroupMasterResponse> activateGroup(@PathVariable UUID groupId) {
        var actor = actors.require();
        return ApiResponse.ok(service.setGroupActive(actor, groupId, true), CorrelationContext.requestId());
    }

    @PutMapping("/customization-groups/{groupId}/deactivate")
    public ApiResponse<CustomizationGroupMasterResponse> deactivateGroup(@PathVariable UUID groupId) {
        var actor = actors.require();
        return ApiResponse.ok(service.setGroupActive(actor, groupId, false), CorrelationContext.requestId());
    }

    @PostMapping("/customization-groups/{groupId}/options")
    public ApiResponse<CustomizationOptionMasterResponse> createOption(@PathVariable UUID groupId,
            @Valid @RequestBody CreateCustomizationOptionRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(service.createOption(actor, groupId, req), CorrelationContext.requestId());
    }

    @PostMapping("/customization-groups/{groupId}/options/reorder")
    public ApiResponse<List<CustomizationOptionMasterResponse>> reorderOptions(@PathVariable UUID groupId,
            @Valid @RequestBody ReorderOptionsRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(service.reorderOptions(actor, groupId, req.optionIds()),
                CorrelationContext.requestId());
    }

    @PatchMapping("/customization-options/{optionId}")
    public ApiResponse<CustomizationOptionMasterResponse> updateOption(@PathVariable UUID optionId,
            @Valid @RequestBody UpdateCustomizationOptionRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(service.updateOption(actor, optionId, req), CorrelationContext.requestId());
    }

    @PutMapping("/customization-options/{optionId}/activate")
    public ApiResponse<CustomizationOptionMasterResponse> activateOption(@PathVariable UUID optionId) {
        var actor = actors.require();
        return ApiResponse.ok(service.setOptionActive(actor, optionId, true), CorrelationContext.requestId());
    }

    @PutMapping("/customization-options/{optionId}/deactivate")
    public ApiResponse<CustomizationOptionMasterResponse> deactivateOption(@PathVariable UUID optionId) {
        var actor = actors.require();
        return ApiResponse.ok(service.setOptionActive(actor, optionId, false), CorrelationContext.requestId());
    }
}
