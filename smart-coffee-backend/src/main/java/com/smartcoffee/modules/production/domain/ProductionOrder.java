package com.smartcoffee.modules.production.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Planned manufacturing order (Slice 5A). This aggregate is INDEPENDENT of the
 * existing {@link ProductionTicket} sale-order kitchen workflow — the two
 * coexist in the {@code production} schema with completely different state
 * graphs, inventory semantics and API surface.
 *
 * <p>Vertical rules enforced here (see spec §4, §5, Phase 5A.1 Decision doc):
 * <ul>
 *   <li>DRAFT is the only editable state — metadata, target and planned
 *       quantity may change.</li>
 *   <li>PLANNED freezes the {@code recipeVersionId}/{@code recipeVersionNumber}
 *       pointers and the {@link ProductionOrderMaterial} + {@link
 *       ProductionOrderOutput} snapshots. Product / Variant / Recipe edits
 *       after this point never mutate the aggregate (spec §5).</li>
 *   <li>IN_PROGRESS records {@code startedAt} + {@code startedBy} only; no
 *       inventory movement is permitted in Phase 5A.1 (completion posting is
 *       Phase 5A.2).</li>
 *   <li>COMPLETED / CANCELLED / CLOSED transitions are declared in the enum
 *       but their write paths (which post ledger rows) arrive with Phase
 *       5A.2. The domain still guards the state graph here so no code
 *       outside the command service can flip these.</li>
 *   <li>Every transition bumps the JPA {@code @Version} column; callers must
 *       echo an {@code expectedVersion} that the service compares before
 *       entering the aggregate.</li>
 * </ul>
 *
 * <p>Referential integrity: a variant, when present, MUST belong to the
 * {@code productId} on this order. That check lives in the service layer
 * because {@code catalog.product_variants} does not expose a composite
 * {@code (product_id, variant_id)} UNIQUE that a DB-level composite FK could
 * target without a broader schema change. The service rejects a mismatch with
 * {@code PRODUCTION_ORDER_TARGET_MISMATCH}.
 *
 * <p>No setter is exposed for the frozen planning columns after
 * {@link #markPlanned}. They are simply not modified again by any domain
 * method: a subsequent product rename, recipe reassignment or output-mapping
 * edit therefore cannot silently rewrite history.
 */
@Entity
@Table(schema = "production", name = "production_orders")
public class ProductionOrder {

    @Id
    @Column(name = "production_order_id")
    private UUID id;
    @Column(name = "production_order_number", nullable = false, unique = true, length = 80)
    private String number;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    @Column(name = "variant_id")
    private UUID variantId;
    // Planning snapshot pointers — populated once at markPlanned, immutable
    // thereafter (spec §5).
    @Column(name = "recipe_version_id")
    private UUID recipeVersionId;
    @Column(name = "recipe_version_number")
    private Integer recipeVersionNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductionOrderStatus status;
    @Column(name = "planned_output_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal plannedOutputQuantity;
    @Column(name = "planned_output_unit_id")
    private UUID plannedOutputUnitId;
    @Column(name = "actual_output_quantity", precision = 18, scale = 4)
    private BigDecimal actualOutputQuantity;
    @Column(name = "scheduled_for")
    private Instant scheduledFor;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "started_by")
    private UUID startedBy;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "completed_by")
    private UUID completedBy;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancelled_by")
    private UUID cancelledBy;
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "closed_by")
    private UUID closedBy;
    @Column(name = "close_note", length = 500)
    private String closeNote;
    @Column(columnDefinition = "text")
    private String notes;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by")
    private UUID updatedBy;
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Immutable once {@link #markPlanned} has been called. The service replaces
     * the whole list at planning time; nothing else appends or mutates rows
     * afterwards (spec §5, Phase 5A.1 material snapshot contract step 14).
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductionOrderMaterial> materials = new ArrayList<>();

    /**
     * One output row is written at planning time (the resolved finished-good
     * inventory item and the planned quantity in its base unit). Phase 5A.2
     * writes the actual quantity and optional batch link on completion.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductionOrderOutput> outputs = new ArrayList<>();

    /** Waste rows are recorded during DRAFT/PLANNED and posted to the ledger in Phase 5A.2. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductionOrderWaste> waste = new ArrayList<>();

    protected ProductionOrder() {
    }

    /**
     * Creates a fresh DRAFT order. Number, store/product/variant existence,
     * recipe validity and output-mapping resolution are the caller's
     * (application service) responsibility; the domain enforces basic
     * quantity/parameter shape only.
     */
    public static ProductionOrder createDraft(String number, UUID storeId, UUID productId, UUID variantId,
            BigDecimal plannedOutputQuantity, UUID plannedOutputUnitId, Instant scheduledFor,
            String notes, UUID actorId) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("Production order number is required");
        }
        if (storeId == null) {
            throw new IllegalArgumentException("Production order requires a store");
        }
        if (productId == null) {
            throw new IllegalArgumentException("Production order requires a product");
        }
        if (plannedOutputQuantity == null || plannedOutputQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Planned output quantity must be greater than zero");
        }
        var o = new ProductionOrder();
        o.id = UUID.randomUUID();
        o.number = number.trim();
        o.storeId = storeId;
        o.productId = productId;
        o.variantId = variantId;
        o.status = ProductionOrderStatus.DRAFT;
        o.plannedOutputQuantity = plannedOutputQuantity;
        o.plannedOutputUnitId = plannedOutputUnitId;
        o.scheduledFor = scheduledFor;
        o.notes = notes;
        var now = Instant.now();
        o.createdAt = now;
        o.updatedAt = now;
        o.createdBy = actorId;
        o.updatedBy = actorId;
        return o;
    }

    // ------------------------------------------------------------------ edits
    // DRAFT-only mutations. Every method below guards via requireDraft() so an
    // accidental call from a service that forgot its state check is a loud
    // IllegalStateException rather than a silent historical rewrite.

    /** Updates the target product/variant and planned quantity. DRAFT only. */
    public void updateDraft(UUID productId, UUID variantId, BigDecimal plannedOutputQuantity,
            UUID plannedOutputUnitId, Instant scheduledFor, String notes, UUID actorId) {
        requireDraft();
        if (productId == null) {
            throw new IllegalArgumentException("Production order requires a product");
        }
        if (plannedOutputQuantity == null || plannedOutputQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Planned output quantity must be greater than zero");
        }
        this.productId = productId;
        this.variantId = variantId;
        this.plannedOutputQuantity = plannedOutputQuantity;
        this.plannedOutputUnitId = plannedOutputUnitId;
        this.scheduledFor = scheduledFor;
        this.notes = notes;
        this.updatedBy = actorId;
        touch();
    }

    /**
     * Freezes the recipe version + material snapshot + output record and
     * transitions DRAFT → PLANNED (spec §5, Phase 5A.1 material snapshot
     * contract). The planner has already computed the normalized lines and
     * the caller must supply the resolved {@code outputIngredientId}; this
     * method only enforces "these fields are set exactly once".
     */
    public void markPlanned(UUID recipeVersionId, int recipeVersionNumber, UUID outputIngredientId,
            String outputUnitCode, List<ProductionOrderMaterial> plannedMaterials,
            ProductionOrderOutput plannedOutput, UUID actorId) {
        requireDraft();
        if (recipeVersionId == null) {
            throw new IllegalStateException("Planning requires a resolved RecipeVersion");
        }
        if (outputIngredientId == null) {
            throw new IllegalStateException("Planning requires an output inventory item");
        }
        if (plannedMaterials == null || plannedMaterials.isEmpty()) {
            throw new IllegalStateException("Planning requires a non-empty material snapshot");
        }
        if (plannedOutput == null) {
            throw new IllegalStateException("Planning requires an output snapshot row");
        }
        this.recipeVersionId = recipeVersionId;
        this.recipeVersionNumber = recipeVersionNumber;
        this.materials.clear();
        for (ProductionOrderMaterial m : plannedMaterials) {
            m.attach(this);
            this.materials.add(m);
        }
        this.outputs.clear();
        plannedOutput.attach(this);
        this.outputs.add(plannedOutput);
        this.status = ProductionOrderStatus.PLANNED;
        this.updatedBy = actorId;
        touch();
    }

    /**
     * PLANNED → IN_PROGRESS. Records actor + timestamp and performs no
     * inventory side-effect (Phase 5A.1 completion-time posting policy; spec
     * §7 recommended policy).
     */
    public void markStarted(UUID actorId) {
        if (status != ProductionOrderStatus.PLANNED) {
            throw new IllegalStateException("Only a PLANNED production order can be started");
        }
        this.startedAt = Instant.now();
        this.startedBy = actorId;
        this.status = ProductionOrderStatus.IN_PROGRESS;
        this.updatedBy = actorId;
        touch();
    }

    /**
     * IN_PROGRESS → COMPLETED. Reserved for Phase 5A.2; the state graph is
     * enforced here so a service that bypasses the future inventory-posting
     * pipeline still fails loudly if it tries to skip completion semantics.
     */
    public void markCompleted(BigDecimal actualGoodOutput, UUID actorId) {
        if (status != ProductionOrderStatus.IN_PROGRESS) {
            throw new IllegalStateException("Only an IN_PROGRESS production order can be completed");
        }
        if (actualGoodOutput == null || actualGoodOutput.signum() < 0) {
            throw new IllegalArgumentException("Actual good output must be non-negative");
        }
        this.actualOutputQuantity = actualGoodOutput;
        this.completedAt = Instant.now();
        this.completedBy = actorId;
        this.status = ProductionOrderStatus.COMPLETED;
        this.updatedBy = actorId;
        touch();
    }

    /**
     * Appends a waste row during completion posting (Phase 5A.2). The
     * aggregate manages the bidirectional attach so the JPA cascade persists
     * it within the same transaction.
     */
    public void addWaste(ProductionOrderWaste w) {
        w.attach(this);
        this.waste.add(w);
    }

    /**
     * Terminal cancellation. Allowed from DRAFT / PLANNED always; from
     * IN_PROGRESS only if no irreversible inventory movement has occurred.
     * In Phase 5A.1 the completion posting pipeline does not exist, so no
     * order can ever have moved stock — therefore IN_PROGRESS cancellation
     * is unconditionally safe in this phase (spec §16, Decision 2). COMPLETED
     * and CLOSED refuse.
     */
    public void cancel(UUID actorId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation requires a reason");
        }
        if (status == ProductionOrderStatus.COMPLETED || status == ProductionOrderStatus.CLOSED) {
            throw new IllegalStateException("Cannot cancel a completed or closed production order");
        }
        if (status == ProductionOrderStatus.CANCELLED) {
            // Idempotent replay: the service short-circuits before arriving
            // here for genuine double-cancel requests, but a redelivered
            // command still lands safely as a no-op.
            return;
        }
        this.cancelledAt = Instant.now();
        this.cancelledBy = actorId;
        this.cancellationReason = reason.trim();
        this.status = ProductionOrderStatus.CANCELLED;
        this.updatedBy = actorId;
        touch();
    }

    /** COMPLETED → CLOSED. Immutable terminal state (spec §17). */
    public void close(UUID actorId, String note) {
        if (status != ProductionOrderStatus.COMPLETED) {
            throw new IllegalStateException("Only a COMPLETED production order can be closed");
        }
        this.closedAt = Instant.now();
        this.closedBy = actorId;
        this.closeNote = (note == null || note.isBlank()) ? null : note.trim();
        this.status = ProductionOrderStatus.CLOSED;
        this.updatedBy = actorId;
        touch();
    }

    private void requireDraft() {
        if (status != ProductionOrderStatus.DRAFT) {
            throw new IllegalStateException("Only a DRAFT production order may be edited");
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ---------------------------------------------------------------- getters
    public UUID id() {return id;}
    public String number() {return number;}
    public UUID storeId() {return storeId;}
    public UUID productId() {return productId;}
    public UUID variantId() {return variantId;}
    public UUID recipeVersionId() {return recipeVersionId;}
    public Integer recipeVersionNumber() {return recipeVersionNumber;}
    public ProductionOrderStatus status() {return status;}
    public BigDecimal plannedOutputQuantity() {return plannedOutputQuantity;}
    public UUID plannedOutputUnitId() {return plannedOutputUnitId;}
    public BigDecimal actualOutputQuantity() {return actualOutputQuantity;}
    public Instant scheduledFor() {return scheduledFor;}
    public Instant startedAt() {return startedAt;}
    public UUID startedBy() {return startedBy;}
    public Instant completedAt() {return completedAt;}
    public UUID completedBy() {return completedBy;}
    public Instant cancelledAt() {return cancelledAt;}
    public UUID cancelledBy() {return cancelledBy;}
    public String cancellationReason() {return cancellationReason;}
    public Instant closedAt() {return closedAt;}
    public UUID closedBy() {return closedBy;}
    public String closeNote() {return closeNote;}
    public String notes() {return notes;}
    public Instant createdAt() {return createdAt;}
    public UUID createdBy() {return createdBy;}
    public Instant updatedAt() {return updatedAt;}
    public UUID updatedBy() {return updatedBy;}
    public long version() {return version;}
    public List<ProductionOrderMaterial> materials() {return List.copyOf(materials);}
    public List<ProductionOrderOutput> outputs() {return List.copyOf(outputs);}
    public List<ProductionOrderWaste> waste() {return List.copyOf(waste);}
}
