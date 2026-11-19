package com.smartcoffee.modules.cart.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.cart.application.CartPromotionQuoteService;
import com.smartcoffee.modules.cart.application.CartService;
import com.smartcoffee.modules.promotion.application.PromotionQuote;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/carts")
public class CartController {
    private final CartService service;
    private final CartPromotionQuoteService promotionQuotes;
    private final CurrentActorProvider actor;

    public CartController(CartService s, CartPromotionQuoteService q, CurrentActorProvider a) {
        service = s;
        promotionQuotes = q;
        actor = a;
    }

    @GetMapping("/current")
    public ApiResponse<CartResponse> current(@RequestParam UUID storeId) {
        return ApiResponse.ok(service.current(actor.require().userId(), storeId), CorrelationContext.requestId());
    }

    /**
     * NEW (Phase B1): authoritative, advisory promotion quote for the caller's
     * owned current cart. The backend resolves the actor, loads the owned cart,
     * builds the quote context from cart snapshots and returns a stable quote; no
     * redemption is reserved and no usage is consumed, so capacity reported here
     * is not guaranteed until checkout reserves atomically (Phase B2).
     */
    @Operation(summary = "Quote a promotion code against the current cart",
            description = "Resolves the authenticated actor's owned ACTIVE cart for the requested store, derives "
                    + "customer, currency, store and line money from server-side snapshots, and evaluates the code "
                    + "with product OR category targeting. Read-only: no redemption is created and usage_count is "
                    + "never incremented.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CART_NOT_FOUND / STORE_NOT_FOUND / RESOURCE_NOT_FOUND (missing customer profile)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "CART_NOT_ACTIVE / CART_EXPIRED")
    })
    @PostMapping("/current/promotion/quote")
    public ApiResponse<PromotionQuote> promotionQuote(@Valid @RequestBody CartPromotionQuoteRequest request) {
        return ApiResponse.ok(
                promotionQuotes.quote(actor.require().userId(), request.storeId(), request.promotionCode()),
                CorrelationContext.requestId());
    }

    /**
     * The {@code Idempotency-Key} header is optional (existing POS/customer-web
     * callers keep working); when supplied, a retry with the same key and payload
     * replays the original cart instead of reserving stock twice (Slice 2C §12).
     */
    @Operation(summary = "Add an item to the current cart",
            description = "Validates the product/variant/customizations, calculates the authoritative "
                    + "ConsumptionPlan demand, reserves the required inventory for this cart's store and only "
                    + "then persists the line. Any shortfall rejects the whole add atomically. Optional "
                    + "Idempotency-Key header makes retries replay the original response without a second "
                    + "reservation.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND / VARIANT_NOT_FOUND / INVENTORY_BALANCE_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "PRODUCT_UNAVAILABLE / CURRENCY_MISMATCH / INSUFFICIENT_STOCK / IDEMPOTENCY_KEY_REUSED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "ACTIVE_RECIPE_REQUIRED / INVALID_CUSTOMIZATION_SELECTION")
    })
    @PostMapping("/current/items")
    public ApiResponse<CartResponse> add(
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody AddCartItemRequest request) {
        return ApiResponse.ok(service.add(actor.require().userId(), idempotencyKey, request),
                CorrelationContext.requestId());
    }

    @PatchMapping("/{cartId}/items/{itemId}")
    public ApiResponse<CartResponse> quantity(@PathVariable UUID cartId, @PathVariable UUID itemId,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return ApiResponse.ok(
                service.updateQuantity(actor.require().userId(), idempotencyKey, cartId, itemId, request.quantity()),
                CorrelationContext.requestId());
    }

    /**
     * Reconfigure an existing line (variant and/or customizations) at the same
     * quantity; inventory is adjusted by per-ingredient delta (Slice 2C §9 D/E).
     */
    @Operation(summary = "Reconfigure a cart line",
            description = "Applies a variant and/or customization change to an existing line. Inventory is "
                    + "re-aligned by the calculated per-ingredient delta — the line's existing hold is never "
                    + "released and re-reserved.")
    @PutMapping("/{cartId}/items/{itemId}/configuration")
    public ApiResponse<CartResponse> configure(@PathVariable UUID cartId, @PathVariable UUID itemId,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody ConfigureCartItemRequest request) {
        return ApiResponse.ok(
                service.configure(actor.require().userId(), idempotencyKey, cartId, itemId, request),
                CorrelationContext.requestId());
    }

    @DeleteMapping("/{cartId}/items/{itemId}")
    public ApiResponse<CartResponse> remove(@PathVariable UUID cartId, @PathVariable UUID itemId) {
        return ApiResponse.ok(service.remove(actor.require().userId(), cartId, itemId), CorrelationContext.requestId());
    }

    @DeleteMapping("/{cartId}/items")
    public ApiResponse<CartResponse> clear(@PathVariable UUID cartId) {
        return ApiResponse.ok(service.clear(actor.require().userId(), cartId), CorrelationContext.requestId());
    }
}
