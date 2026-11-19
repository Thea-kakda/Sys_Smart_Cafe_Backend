package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.inventory.application.UnitOfMeasureCommandService;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Inventory — Units of Measure", description = "Unit-of-measure master data")
@RestController
@RequestMapping("/api/v1/units-of-measure")
public class UnitOfMeasureController {
    private final UnitOfMeasureRepository units;
    private final UnitOfMeasureCommandService commands;
    private final CurrentActorProvider actor;
    private final PermissionEvaluator permissions;

    public UnitOfMeasureController(UnitOfMeasureRepository u, UnitOfMeasureCommandService commands,
            CurrentActorProvider actor, PermissionEvaluator permissions) {
        units = u;
        this.commands = commands;
        this.actor = actor;
        this.permissions = permissions;
    }

    @Operation(summary = "List active units of measure",
            description = "The POS/conversion-facing roster (active only, ordered by type/code). No permission — "
                    + "identical to the historical behaviour.")
    @GetMapping
    public ApiResponse<List<UnitOfMeasureResponse>> list() {
        return ApiResponse.ok(
                units.findByActiveTrueOrderByTypeAscCodeAsc().stream().map(x -> new UnitOfMeasureResponse(x.id(),
                        x.code(), x.name(), x.type().name(), x.decimalScale(), x.factor(), x.baseUnit())).toList(),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Management list of all units (incl. inactive)",
            description = "Full roster with active flag, timestamps and optimistic-lock version for the Dashboard "
                    + "master editor. Permission: uom:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks uom:manage")
    })
    @GetMapping("/management")
    public ApiResponse<List<UnitManagementResponse>> managementList() {
        var x = actor.require();
        permissions.require(x, PermissionCodes.UOM_MANAGE);
        return ApiResponse.ok(units.findAll().stream().map(UnitOfMeasureCommandService::toResponse).toList(),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Create a unit of measure",
            description = "Creates a unit master record. Code uniqueness -> 409 DUPLICATE_CODE. "
                    + "Permission: uom:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks uom:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_CODE")
    })
    @PostMapping
    public ApiResponse<UnitManagementResponse> create(@Valid @RequestBody CreateUnitRequest request) {
        var x = actor.require();
        return ApiResponse.ok(commands.create(x, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Update a unit of measure",
            description = "Full-replacement update. Stale version -> 409 VERSION_CONFLICT; code clash -> 409 "
                    + "DUPLICATE_CODE; unknown -> 404 UNIT_NOT_FOUND. Permission: uom:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks uom:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "UNIT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT / DUPLICATE_CODE")
    })
    @PatchMapping("/{unitId}")
    public ApiResponse<UnitManagementResponse> update(@PathVariable UUID unitId,
            @Valid @RequestBody UpdateUnitRequest request) {
        var x = actor.require();
        return ApiResponse.ok(commands.update(x, unitId, request), CorrelationContext.requestId());
    }
}
