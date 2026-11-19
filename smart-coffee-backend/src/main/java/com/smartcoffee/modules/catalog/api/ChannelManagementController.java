package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.application.ChannelManagementService;
import com.smartcoffee.modules.catalog.application.ChannelManagementService.ChannelScheduleView;
import com.smartcoffee.modules.catalog.domain.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Channel publication management API (Slice 3B, spec §18). Authenticated and
 * store-scoped; unlike the public catalog these endpoints MAY accept an explicit
 * controlled {@link Channel} because the caller is authorized. Every write is
 * optimistic-locked and never mutates inventory. The manual sold-out control maps
 * onto the existing store-product fields, preserving spec §6 semantics.
 */
@Tag(name = "Catalog Channels", description = "Per-channel publication, schedules and manual sold-out management")
@RestController
@RequestMapping("/api/v1/management/catalog/stores/{storeId}/products/{productId}")
public class ChannelManagementController {

    private final ChannelManagementService service;
    private final CurrentActorProvider actors;

    public ChannelManagementController(ChannelManagementService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @Operation(summary = "List effective channel publication policy for a product")
    @GetMapping("/channels")
    public ApiResponse<List<ChannelPolicyResponse>> listChannels(@PathVariable UUID storeId,
            @PathVariable UUID productId) {
        return ApiResponse.ok(service.listChannels(actors.require(), storeId, productId),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Create or replace a channel publication (optimistic-locked)")
    @PutMapping("/channels/{channel}")
    public ApiResponse<ChannelPolicyResponse> upsertChannel(@PathVariable UUID storeId, @PathVariable UUID productId,
            @PathVariable String channel, @Valid @RequestBody ChannelConfigurationRequest request) {
        return ApiResponse.ok(service.upsertChannel(actors.require(), storeId, productId, parse(channel), request),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Remove a channel publication (reverts to the inherited default)")
    @DeleteMapping("/channels/{channel}")
    public ApiResponse<Void> deleteChannel(@PathVariable UUID storeId, @PathVariable UUID productId,
            @PathVariable String channel) {
        service.deleteChannel(actors.require(), storeId, productId, parse(channel));
        return ApiResponse.ok(null, CorrelationContext.requestId());
    }

    @Operation(summary = "Preview the channel catalog projection for a product (read-only)")
    @GetMapping("/channels/{channel}/preview")
    public ApiResponse<ProductDetailsResponse> previewChannel(@PathVariable UUID storeId, @PathVariable UUID productId,
            @PathVariable String channel) {
        return ApiResponse.ok(service.previewChannelCatalog(actors.require(), storeId, productId, parse(channel)),
                CorrelationContext.requestId());
    }

    @Operation(summary = "List a channel's weekly schedule windows")
    @GetMapping("/channels/{channel}/schedules")
    public ApiResponse<List<ChannelScheduleView>> listSchedules(@PathVariable UUID storeId, @PathVariable UUID productId,
            @PathVariable String channel) {
        return ApiResponse.ok(service.listSchedules(actors.require(), storeId, productId, parse(channel)),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Replace a channel's weekly schedule windows (authoritative list)")
    @PutMapping("/channels/{channel}/schedules")
    public ApiResponse<List<ChannelScheduleView>> replaceSchedules(@PathVariable UUID storeId,
            @PathVariable UUID productId, @PathVariable String channel,
            @Valid @RequestBody ReplaceSchedulesRequest request) {
        return ApiResponse.ok(service.replaceSchedules(actors.require(), storeId, productId, parse(channel), request),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Set the manual sold-out control for a store product")
    @PatchMapping("/sold-out")
    public ApiResponse<Void> setSoldOut(@PathVariable UUID storeId, @PathVariable UUID productId,
            @Valid @RequestBody ManualSoldOutRequest request) {
        service.setManualSoldOut(actors.require(), storeId, productId, request);
        return ApiResponse.ok(null, CorrelationContext.requestId());
    }

    private static Channel parse(String channel) {
        try {
            return Channel.from(channel);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException("INVALID_CHANNEL", "Unknown channel: " + channel);
        }
    }
}
