package com.smartcoffee.modules.production.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.infrastructure.idempotency.ApiIdempotencyRecord;
import com.smartcoffee.infrastructure.idempotency.CanonicalRequestHasher;
import com.smartcoffee.infrastructure.idempotency.IdempotencyService;
import com.smartcoffee.infrastructure.idempotency.IdempotencyStatus;
import com.smartcoffee.infrastructure.messaging.EventEnvelope;
import com.smartcoffee.infrastructure.messaging.outbox.OutboxEventWriter;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.production.api.CancelProductionOrderRequest;
import com.smartcoffee.modules.production.api.CloseProductionOrderRequest;
import com.smartcoffee.modules.production.api.CompleteProductionOrderRequest;
import com.smartcoffee.modules.production.api.CreateProductionOrderRequest;
import com.smartcoffee.modules.production.api.PlanProductionOrderRequest;
import com.smartcoffee.modules.production.api.ProductionOrderResponse;
import com.smartcoffee.modules.production.api.StartProductionOrderRequest;
import com.smartcoffee.modules.production.api.UpdateProductionOrderRequest;
import com.smartcoffee.modules.production.domain.ProductionOrder;
import com.smartcoffee.modules.production.domain.ProductionOrderStatus;
import com.smartcoffee.modules.production.persistence.ProductionOrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Write path for the {@link ProductionOrder} aggregate (Slice 5A §21, Phase
 * 5A.1). Owns createDraft / updateDraft / plan / start. Completion,
 * cancellation and close transitions are wired in Phase 5A.2; the state-graph
 * guards for them already live on the aggregate so a service that forgot its
 * state check still fails loudly.
 *
 * <p>Every command performs, in order: (1) idempotency claim, (2) permission
 * + Store scope check by the controller, (3) pessimistic aggregate lock, (4)
 * optimistic-version guard, (5) domain transition, (6) outbox append, (7)
 * idempotency completion. All inside a single {@code @Transactional} boundary
 * — the DB commit is the sole atomic point of truth.
 *
 * <p>Inventory movement: NONE in Phase 5A.1. DRAFT, PLANNED and IN_PROGRESS
 * transitions write no {@code stock_transactions} row and no {@code
 * inventory_balances} update. Completion-time posting is Phase 5A.2.
 */
@Service
public class ProductionOrderCommandService {

    /** Idempotency scopes. Values align with the existing PRODUCTION_* family. */
    static final String SCOPE_CREATE = "PRODUCTION_ORDER_CREATE";
    static final String SCOPE_UPDATE = "PRODUCTION_ORDER_UPDATE";
    static final String SCOPE_PLAN = "PRODUCTION_ORDER_PLAN";
    static final String SCOPE_START = "PRODUCTION_ORDER_START";
    static final String SCOPE_COMPLETE = "PRODUCTION_ORDER_COMPLETE";
    static final String SCOPE_CANCEL = "PRODUCTION_ORDER_CANCEL";
    static final String SCOPE_CLOSE = "PRODUCTION_ORDER_CLOSE";

    private final ProductionOrderRepository orders;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductionOrderNumberGenerator numbers;
    private final ProductionMaterialPlanner planner;
    private final StoreAuthorizationService stores;
    private final IdempotencyService idem;
    private final CanonicalRequestHasher hasher;
    private final OutboxEventWriter outbox;
    private final ProductionOrderMapper mapper;
    private final ObjectMapper json;
    private final ProductionInventoryService inventory;
    private final AuditWriter audit;

    public ProductionOrderCommandService(ProductionOrderRepository orders,
            ProductRepository products, ProductVariantRepository variants,
            ProductionOrderNumberGenerator numbers, ProductionMaterialPlanner planner,
            StoreAuthorizationService stores, IdempotencyService idem, CanonicalRequestHasher hasher,
            OutboxEventWriter outbox, ProductionOrderMapper mapper, ObjectMapper json,
            ProductionInventoryService inventory, AuditWriter audit) {
        this.orders = orders;
        this.products = products;
        this.variants = variants;
        this.numbers = numbers;
        this.planner = planner;
        this.stores = stores;
        this.idem = idem;
        this.hasher = hasher;
        this.outbox = outbox;
        this.mapper = mapper;
        this.json = json;
        this.inventory = inventory;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ create
    @Transactional
    public ProductionOrderResponse createDraft(CurrentActor actor, UUID idemKey, CreateProductionOrderRequest req) {
        var claim = idem.claim(SCOPE_CREATE, "USER", actor.userId(), idemKey,
                hasher.hash(req), Duration.ofDays(1));
        // IdempotencyService already throws ConflictException("IDEMPOTENCY_KEY_REUSED")
        // on a hash mismatch and ConflictException("IDEMPOTENCY_REQUEST_IN_PROGRESS")
        // on a concurrent same-key attempt. The only non-throwing branch is a
        // previously COMPLETED record, which we replay verbatim (spec §14).
        if (claim.status() == IdempotencyStatus.COMPLETED) {
            return replay(claim);
        }
        stores.requireStore(actor, req.storeId());
        Product product = products.findById(req.productId())
                .orElseThrow(() -> new ValidationException("PRODUCT_NOT_FOUND", "Product not found"));
        if (req.variantId() != null) {
            ProductVariant v = variants.findByIdAndDeletedAtIsNull(req.variantId())
                    .orElseThrow(() -> new ValidationException("VARIANT_NOT_FOUND", "Variant not found or deleted"));
            if (!v.productId().equals(product.id())) {
                throw new ValidationException("PRODUCTION_ORDER_TARGET_MISMATCH",
                        "Variant does not belong to the selected product");
            }
        }
        if (req.plannedOutputQuantity() == null || req.plannedOutputQuantity().signum() <= 0) {
            throw new ValidationException("PRODUCTION_OUTPUT_QUANTITY_INVALID",
                    "Planned output quantity must be greater than zero");
        }
        String number = numbers.next();
        var order = ProductionOrder.createDraft(number, req.storeId(), req.productId(), req.variantId(),
                req.plannedOutputQuantity(), req.plannedOutputUnitId(), req.scheduledFor(), req.notes(),
                actor.userId());
        orders.save(order);
        emit(order, "ProductionOrderDrafted", "production.order.drafted.v1");
        return complete(claim, mapper.toResponse(order));
    }

    // ------------------------------------------------------------------ update
    @Transactional
    public ProductionOrderResponse updateDraft(CurrentActor actor, UUID idemKey, UUID productionOrderId,
            UpdateProductionOrderRequest req) {
        var claim = idem.claim(SCOPE_UPDATE, "USER", actor.userId(), idemKey,
                hasher.hash(new UpdateScope(productionOrderId, req)), Duration.ofDays(1));
        if (claim.status() == IdempotencyStatus.COMPLETED) {
            return replay(claim);
        }
        ProductionOrder order = lockOrThrow(productionOrderId);
        stores.requireStore(actor, order.storeId());
        requireVersion(order, req.expectedVersion());
        if (order.status() != ProductionOrderStatus.DRAFT) {
            throw new ConflictException("PRODUCTION_ORDER_STATE_CONFLICT",
                    "Only a DRAFT production order may be edited");
        }
        order.updateDraft(order.productId(), order.variantId(), req.plannedOutputQuantity(),
                req.plannedOutputUnitId(), req.scheduledFor(), req.notes(), actor.userId());
        orders.save(order);
        emit(order, "ProductionOrderUpdated", "production.order.updated.v1");
        return complete(claim, mapper.toResponse(order));
    }

    /** Update-side idempotency payload: order id + request body. */
    private record UpdateScope(UUID orderId, UpdateProductionOrderRequest req) {}

    // -------------------------------------------------------------------- plan
    @Transactional
    public ProductionOrderResponse plan(CurrentActor actor, UUID idemKey, UUID productionOrderId,
            PlanProductionOrderRequest req) {
        var claim = idem.claim(SCOPE_PLAN, "USER", actor.userId(), idemKey,
                hasher.hash(new OrderScope(productionOrderId, req)), Duration.ofDays(1));
        if (claim.status() == IdempotencyStatus.COMPLETED) {
            return replay(claim);
        }
        ProductionOrder order = lockOrThrow(productionOrderId);
        stores.requireStore(actor, order.storeId());
        requireVersion(order, req.expectedVersion());
        if (order.status() != ProductionOrderStatus.DRAFT) {
            throw new ConflictException("PRODUCTION_ORDER_STATE_CONFLICT",
                    "Only a DRAFT production order may be planned");
        }
        ProductionMaterialPlanner.Plan plan = planner.plan(
                order.productId(), order.variantId(), order.plannedOutputQuantity());
        order.markPlanned(
                plan.recipeVersion().id(),
                plan.recipeVersion().versionNumber(),
                plan.outputInventoryItemId(),
                plan.outputUnitCode(),
                plan.materials(),
                plan.output(),
                actor.userId());
        orders.save(order);
        emit(order, "ProductionOrderPlanned", "production.order.planned.v1");
        return complete(claim, mapper.toResponse(order));
    }

    /** Plan/Start idempotency payload: order id + request body. */
    private record OrderScope(UUID orderId, Object req) {}

    // ------------------------------------------------------------------- start
    @Transactional
    public ProductionOrderResponse start(CurrentActor actor, UUID idemKey, UUID productionOrderId,
            StartProductionOrderRequest req) {
        var claim = idem.claim(SCOPE_START, "USER", actor.userId(), idemKey,
                hasher.hash(new OrderScope(productionOrderId, req)), Duration.ofDays(1));
        if (claim.status() == IdempotencyStatus.COMPLETED) {
            return replay(claim);
        }
        ProductionOrder order = lockOrThrow(productionOrderId);
        stores.requireStore(actor, order.storeId());
        requireVersion(order, req.expectedVersion());
        if (order.status() == ProductionOrderStatus.IN_PROGRESS) {
            // Idempotent "already started" short-circuit. Same request body /
            // same key would have been caught by the claim above; a fresh
            // caller trying to re-start is answered with a stable conflict.
            throw new ConflictException("PRODUCTION_ALREADY_STARTED",
                    "Production order is already in progress");
        }
        if (order.status() != ProductionOrderStatus.PLANNED) {
            throw new ConflictException("PRODUCTION_ORDER_STATE_CONFLICT",
                    "Only a PLANNED production order may be started");
        }
        order.markStarted(actor.userId());
        orders.save(order);
        emit(order, "ProductionOrderStarted", "production.order.started.v1");
        return complete(claim, mapper.toResponse(order));
    }

    // ------------------------------------------------------------------ complete
    /**
     * Completes an IN_PROGRESS production order: posts material consumption
     * and finished-good output atomically (Slice 5A.2 §8).
     */
    @Transactional
    public ProductionOrderResponse complete(CurrentActor actor, UUID idemKey, UUID productionOrderId,
            CompleteProductionOrderRequest req) {
        var claim = idem.claim(SCOPE_COMPLETE, "USER", actor.userId(), idemKey,
                hasher.hash(new OrderScope(productionOrderId, req)), Duration.ofDays(1));
        if (claim.status() == IdempotencyStatus.COMPLETED) {
            return replay(claim);
        }
        ProductionOrder order = lockOrThrow(productionOrderId);
        stores.requireStore(actor, order.storeId());
        requireVersion(order, req.expectedVersion());
        if (order.status() != ProductionOrderStatus.IN_PROGRESS) {
            throw new ConflictException("PRODUCTION_ORDER_STATE_CONFLICT",
                    "Only an IN_PROGRESS production order can be completed");
        }
        if (req.actualGoodOutputQuantity() == null || req.actualGoodOutputQuantity().signum() < 0) {
            throw new ValidationException("PRODUCTION_OUTPUT_QUANTITY_INVALID",
                    "Actual good output must be non-negative");
        }
        // Validate output item exists
        if (order.outputs().isEmpty()) {
            throw new ConflictException("PRODUCTION_OUTPUT_ITEM_REQUIRED",
                    "No output snapshot on this order — planning was incomplete");
        }

        // Invoke inventory posting (may throw, rolls back the whole transaction)
        inventory.postCompletion(order, req.actualGoodOutputQuantity(),
                req.wasteEntries(), req.manufacturedDate(), req.expiryDate(),
                req.lotNumber(), actor.userId(), idemKey.toString());

        // Transition the aggregate
        order.markCompleted(req.actualGoodOutputQuantity(), actor.userId());
        orders.save(order);

        emit(order, "ProductionOrderCompleted", "production.order.completed.v1");
        audit.success(actor.userId(), order.storeId(), "PRODUCTION_ORDER_COMPLETED", "PRODUCTION",
                "PRODUCTION_ORDER", order.id(), null,
                Map.of("status", order.status().name(), "actualOutput", req.actualGoodOutputQuantity()),
                "Completed production order " + order.number());
        return complete(claim, mapper.toResponse(order));
    }

    // ------------------------------------------------------------------ cancel
    /**
     * Cancels a DRAFT/PLANNED/eligible-IN_PROGRESS order (Slice 5A.2 §9).
     * Rejects if production inventory transactions already exist.
     */
    @Transactional
    public ProductionOrderResponse cancel(CurrentActor actor, UUID idemKey, UUID productionOrderId,
            CancelProductionOrderRequest req) {
        var claim = idem.claim(SCOPE_CANCEL, "USER", actor.userId(), idemKey,
                hasher.hash(new OrderScope(productionOrderId, req)), Duration.ofDays(1));
        if (claim.status() == IdempotencyStatus.COMPLETED) {
            return replay(claim);
        }
        ProductionOrder order = lockOrThrow(productionOrderId);
        stores.requireStore(actor, order.storeId());
        requireVersion(order, req.expectedVersion());
        if (req.reason() == null || req.reason().isBlank()) {
            throw new ValidationException("VALIDATION_ERROR", "Cancellation reason is required");
        }
        // Terminal-state guard (spec §12): COMPLETED / CLOSED / already-CANCELLED
        // reject with a stable 409 rather than leaking a domain IllegalStateException.
        if (order.status() != ProductionOrderStatus.DRAFT
                && order.status() != ProductionOrderStatus.PLANNED
                && order.status() != ProductionOrderStatus.IN_PROGRESS) {
            throw new ConflictException("PRODUCTION_ORDER_STATE_CONFLICT",
                    "Only a DRAFT, PLANNED or eligible IN_PROGRESS order may be cancelled");
        }
        // Guard: IN_PROGRESS with existing production ledger → require recovery
        if (order.status() == ProductionOrderStatus.IN_PROGRESS
                && inventory.hasProductionLedger(order.id())) {
            throw new ConflictException("PRODUCTION_CANCELLATION_REQUIRES_RECOVERY",
                    "Production inventory transactions exist; a recovery path is required");
        }
        order.cancel(actor.userId(), req.reason());
        orders.save(order);

        emit(order, "ProductionOrderCancelled", "production.order.cancelled.v1");
        audit.success(actor.userId(), order.storeId(), "PRODUCTION_ORDER_CANCELLED", "PRODUCTION",
                "PRODUCTION_ORDER", order.id(), null,
                Map.of("status", order.status().name(), "reason", req.reason()),
                "Cancelled production order " + order.number());
        return complete(claim, mapper.toResponse(order));
    }

    // ------------------------------------------------------------------ close
    /**
     * Closes a COMPLETED order (terminal state, Slice 5A.2 §10). No inventory
     * movement, audit and outbox only.
     */
    @Transactional
    public ProductionOrderResponse close(CurrentActor actor, UUID idemKey, UUID productionOrderId,
            CloseProductionOrderRequest req) {
        var claim = idem.claim(SCOPE_CLOSE, "USER", actor.userId(), idemKey,
                hasher.hash(new OrderScope(productionOrderId, req)), Duration.ofDays(1));
        if (claim.status() == IdempotencyStatus.COMPLETED) {
            return replay(claim);
        }
        ProductionOrder order = lockOrThrow(productionOrderId);
        stores.requireStore(actor, order.storeId());
        requireVersion(order, req.expectedVersion());
        // Only a COMPLETED order may close (spec §10); reject every other state
        // with a stable 409 instead of a domain IllegalStateException (500).
        if (order.status() != ProductionOrderStatus.COMPLETED) {
            throw new ConflictException("PRODUCTION_ORDER_STATE_CONFLICT",
                    "Only a COMPLETED production order may be closed");
        }
        order.close(actor.userId(), req.note());
        orders.save(order);

        emit(order, "ProductionOrderClosed", "production.order.closed.v1");
        audit.success(actor.userId(), order.storeId(), "PRODUCTION_ORDER_CLOSED", "PRODUCTION",
                "PRODUCTION_ORDER", order.id(), null,
                Map.of("status", order.status().name()),
                "Closed production order " + order.number());
        return complete(claim, mapper.toResponse(order));
    }

    // ------------------------------------------------------------------ helpers
    private ProductionOrder lockOrThrow(UUID id) {
        return orders.lock(id).orElseThrow(() -> new ResourceNotFoundException(
                "PRODUCTION_ORDER_NOT_FOUND", "Production order not found"));
    }

    private void requireVersion(ProductionOrder order, long expected) {
        if (order.version() != expected) {
            throw new ConflictException("PRODUCTION_ORDER_VERSION_CONFLICT",
                    "Production order changed under you; refresh and retry");
        }
    }

    private void emit(ProductionOrder order, String type, String route) {
        ObjectNode payload = json.createObjectNode()
                .put("productionOrderId", order.id().toString())
                .put("productionOrderNumber", order.number())
                .put("storeId", order.storeId().toString())
                .put("productId", order.productId().toString())
                .put("status", order.status().name());
        if (order.variantId() != null) {
            payload.put("variantId", order.variantId().toString());
        }
        if (order.recipeVersionId() != null) {
            payload.put("recipeVersionId", order.recipeVersionId().toString());
        }
        outbox.append(new EventEnvelope(UUID.randomUUID(), type, 1,
                "PRODUCTION_ORDER", order.id(), order.storeId(), Instant.now(),
                null, null, payload), route);
    }

    private ProductionOrderResponse complete(ApiIdempotencyRecord claim, ProductionOrderResponse response) {
        try {
            idem.complete(claim, 200, json.writeValueAsString(response));
            return response;
        } catch (Exception e) {
            throw new ConflictException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot store response");
        }
    }

    private ProductionOrderResponse replay(ApiIdempotencyRecord claim) {
        try {
            return json.readValue(claim.responseBody(), ProductionOrderResponse.class);
        } catch (Exception e) {
            throw new ConflictException("IDEMPOTENCY_RESPONSE_ERROR", "Cannot replay response");
        }
    }

    /** Exposed for the Query service to reuse the same lock helper (read-only). */
    @Transactional(readOnly = true)
    public List<ProductionOrderStatus> statusValues() {
        return List.of(ProductionOrderStatus.values());
    }
}
