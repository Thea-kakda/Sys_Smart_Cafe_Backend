package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.domain.StockMode;
import com.smartcoffee.modules.catalog.persistence.CustomizationGroupRepository;
import com.smartcoffee.modules.catalog.persistence.CustomizationOptionRepository;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import com.smartcoffee.modules.cart.application.CartCatalogGateway;
import com.smartcoffee.modules.cart.application.CartCustomizationValidator;
import com.smartcoffee.modules.cart.domain.CartItemCustomization;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import com.smartcoffee.modules.recipe.application.RecipeLineNormalizer.NormalizedLine;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Resolved;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic, READ-ONLY Calculated Consumption Plan (Slice 2B). For one
 * configured order item it combines the resolved ACTIVE recipe, its lines, the
 * requested quantity and the validated customization inventory effects into the
 * authoritative per-ingredient demand — expressed in each ingredient's base
 * inventory unit.
 *
 * <p>Hard boundaries honored by this class: it performs <b>no</b> reservation /
 * consumption / ledger write, mutates <b>no</b> recipe / option / stock entity,
 * touches <b>no</b> cart / order / checkout path, and never trusts client-supplied
 * ingredient ids, quantities, effects, prices or multipliers — every authoritative
 * value is loaded from the database. The existing {@code RecipeDemandService} and
 * {@code InventoryEffectCalculator} are left untouched; this engine generalizes the
 * same per-effect arithmetic with canonical phase ordering, option-quantity and a
 * single product-quantity scale.
 *
 * <p>Pipeline (spec §5): validate product/variant/quantity/customizations → resolve
 * recipe → normalize lines to per-unit base demand → apply effects in canonical
 * order (REMOVE → REPLACE → MULTIPLY → ADD) → scale by product quantity exactly
 * once → round to scale 4 CEILING → drop zero lines → deterministic output.
 */
@Service
public class ConsumptionPlanService {

    /** Sellable-quantity upper bound for a single preview line (abuse guard). */
    private static final BigDecimal MAX_PRODUCT_QUANTITY = new BigDecimal("100000");
    /** Final-quantity sanity bound: numeric(18,4) storage ceiling. */
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("100000000000000");
    /** Maximum distinct customization selections accepted for one item. */
    private static final int MAX_SELECTED_OPTIONS = 200;

    private final RecipeVersionResolver resolver;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CartCatalogGateway catalogGateway;
    private final CartCustomizationValidator customizationValidator;
    private final CustomizationGroupRepository groups;
    private final CustomizationOptionRepository options;
    private final RecipeLineNormalizer lineNormalizer;
    private final IngredientRepository ingredients;
    private final UnitOfMeasureRepository units;
    private final PermissionEvaluator permissions;

    public ConsumptionPlanService(RecipeVersionResolver resolver, ProductRepository products,
            ProductVariantRepository variants, CartCatalogGateway catalogGateway,
            CartCustomizationValidator customizationValidator, CustomizationGroupRepository groups,
            CustomizationOptionRepository options, RecipeLineNormalizer lineNormalizer,
            IngredientRepository ingredients, UnitOfMeasureRepository units, PermissionEvaluator permissions) {
        this.resolver = resolver;
        this.products = products;
        this.variants = variants;
        this.catalogGateway = catalogGateway;
        this.customizationValidator = customizationValidator;
        this.groups = groups;
        this.options = options;
        this.lineNormalizer = lineNormalizer;
        this.ingredients = ingredients;
        this.units = units;
        this.permissions = permissions;
    }

    /**
     * Computes the plan for one configured item. {@code variantId} may be null
     * (product-level). {@code productQuantity} is the sellable quantity. All
     * customization selections are validated against the product's catalog rules.
     */
    @Transactional(readOnly = true)
    public CalculatedConsumptionPlan preview(CurrentActor actor, UUID productId, UUID variantId,
            BigDecimal productQuantity, List<SelectedCustomizationRequest> selected) {
        permissions.require(actor, PermissionCodes.RECIPE_VIEW);
        return calculate(productId, variantId, productQuantity, selected);
    }

    /**
     * Authoritative plan without the management RECIPE_VIEW check (Slice 2C). Cart
     * add / quantity change / reconfiguration must reserve exactly this demand for
     * an authenticated customer, who holds no management permission. Every
     * validation, resolution rule, effect ordering, quantity scaling and overflow
     * guard below is unchanged — only the caller's permission differs.
     */
    @Transactional(readOnly = true)
    public CalculatedConsumptionPlan plan(UUID productId, UUID variantId, BigDecimal productQuantity,
            List<SelectedCustomizationRequest> selected) {
        return calculate(productId, variantId, productQuantity, selected);
    }

    private CalculatedConsumptionPlan calculate(UUID productId, UUID variantId, BigDecimal productQuantity,
            List<SelectedCustomizationRequest> selected) {
        List<String> warnings = new ArrayList<>();
        BigDecimal quantity = validateProductQuantity(productQuantity);
        Product product = validateProduct(productId);
        validateVariant(productId, variantId);
        List<CartItemCustomization> validatedSelections = validateSelections(productId, selected);

        // §9 No-recipe behavior per StockMode. Only make-to-order / recipe-based
        // items derive raw-ingredient demand in 2B; other modes return an empty
        // NONE plan rather than fabricating movement.
        switch (product.stockMode()) {
            case NOT_TRACKED -> {
                return emptyPlan(productId, variantId, quantity, SourceHint.NONE_WITH_WARNING,
                        "Product is not inventory-tracked; no ingredient consumption planned.");
            }
            case FINISHED_GOOD -> {
                return emptyPlan(productId, variantId, quantity, SourceHint.NONE_WITH_WARNING,
                        "Finished-good stock mapping is deferred to a later slice; no raw demand invented.");
            }
            case BATCH_PRODUCTION -> {
                return emptyPlan(productId, variantId, quantity, SourceHint.NONE_WITH_WARNING,
                        "Batch/production-order consumption is out of scope for sale-time planning; deferred.");
            }
            default -> { /* MADE_TO_ORDER / RECIPE_BASED continue below */ }
        }

        Resolved resolved = resolver.resolve(productId, variantId);
        if (!resolved.present()) {
            if (product.stockMode() == StockMode.RECIPE_BASED) {
                throw new ValidationException("ACTIVE_RECIPE_REQUIRED",
                        "This product is recipe-based but has no ACTIVE recipe to plan against.");
            }
            // MADE_TO_ORDER legacy product without a recipe: empty plan, not an error.
            return emptyPlan(productId, variantId, quantity, SourceHint.NONE_OK,
                    "Product has no ACTIVE recipe; nothing to consume.");
        }

        RecipeVersion version = resolved.recipeVersion();
        List<NormalizedLine> baseLines = lineNormalizer.normalize(version);

        // Working per-unit demand (UNROUNDED, base unit) + trace, keyed by ingredient.
        Map<UUID, BigDecimal> perUnit = new LinkedHashMap<>();
        Map<UUID, EnumSet<ConsumptionLineSource>> sources = new LinkedHashMap<>();
        Map<UUID, LinkedHashSet<UUID>> appliedOptions = new LinkedHashMap<>();
        Map<UUID, Integer> displayOrders = new LinkedHashMap<>();
        Map<UUID, Boolean> optionalOnly = new LinkedHashMap<>();
        for (NormalizedLine line : baseLines) {
            perUnit.put(line.ingredientId(), line.perUnit());
            sources.put(line.ingredientId(), EnumSet.of(ConsumptionLineSource.BASE_RECIPE));
            displayOrders.put(line.ingredientId(), line.displayOrder());
            optionalOnly.put(line.ingredientId(), line.optional());
        }

        applyEffects(validatedSelections, perUnit, sources, appliedOptions, displayOrders, optionalOnly);

        // §Step 9: apply the requested product quantity EXACTLY ONCE, then round.
        List<CalculatedConsumptionLine> resultLines = materialize(perUnit, sources, appliedOptions,
                displayOrders, optionalOnly, quantity);

        return new CalculatedConsumptionPlan(
                productId, variantId, version.id(), version.versionNumber(), resolved.source(),
                quantity, Instant.now(), resultLines, warnings);
    }

    // ------------------------------------------------------------- validation

    private BigDecimal validateProductQuantity(BigDecimal productQuantity) {
        if (productQuantity == null || productQuantity.signum() <= 0) {
            throw new ValidationException("INVALID_PRODUCT_QUANTITY", "Product quantity must be greater than zero");
        }
        if (productQuantity.stripTrailingZeros().scale() > 4) {
            throw new ValidationException("INVALID_PRODUCT_QUANTITY",
                    "Product quantity scale must not exceed 4 decimal places");
        }
        if (productQuantity.compareTo(MAX_PRODUCT_QUANTITY) > 0) {
            throw new ValidationException("INVALID_PRODUCT_QUANTITY",
                    "Product quantity exceeds the maximum allowed for a single item");
        }
        return productQuantity;
    }

    private Product validateProduct(UUID productId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
        if (!product.active()) {
            throw new ValidationException("PRODUCT_INACTIVE", "Product is inactive");
        }
        return product;
    }

    private void validateVariant(UUID productId, UUID variantId) {
        if (variantId == null) {
            return;
        }
        ProductVariant variant = variants.findByIdAndDeletedAtIsNull(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("VARIANT_NOT_FOUND", "Variant not found"));
        if (!Objects.equals(variant.productId(), productId)) {
            throw new ValidationException("VARIANT_PRODUCT_MISMATCH",
                    "Variant does not belong to the requested product");
        }
        if (!variant.active()) {
            throw new ValidationException("VARIANT_INACTIVE", "Variant is inactive");
        }
    }

    private List<CartItemCustomization> validateSelections(UUID productId, List<SelectedCustomizationRequest> selected) {
        if (selected != null && selected.size() > MAX_SELECTED_OPTIONS) {
            throw new ValidationException("INVALID_CUSTOMIZATION_SELECTION",
                    "Too many customization selections for a single item");
        }
        List<CartCatalogGateway.GroupRule> rules = catalogGateway.groupRules(productId);
        // Reuses the cart validator: enforces required / min / max / SINGLE, active
        // options, group ownership and duplicate-option rejection exactly as checkout.
        return customizationValidator.validate(rules, selected);
    }

    // ---------------------------------------------------------------- effects

    @SuppressWarnings("null") // comparator key extractors are non-null by construction
    private void applyEffects(List<CartItemCustomization> selections, Map<UUID, BigDecimal> perUnit,
            Map<UUID, EnumSet<ConsumptionLineSource>> sources, Map<UUID, LinkedHashSet<UUID>> appliedOptions,
            Map<UUID, Integer> displayOrders, Map<UUID, Boolean> optionalOnly) {
        if (selections.isEmpty()) {
            return;
        }
        Map<UUID, Integer> selectionQty = new LinkedHashMap<>();
        for (CartItemCustomization c : selections) {
            selectionQty.put(c.optionId(), c.quantity());
        }
        List<UUID> optionIds = new ArrayList<>(selectionQty.keySet());
        Map<UUID, CustomizationOption> optionById = new LinkedHashMap<>();
        options.findAllById(optionIds).forEach(o -> optionById.put(o.id(), o));

        List<CustomizationOption> effectOptions = new ArrayList<>();
        for (UUID id : optionIds) {
            CustomizationOption o = optionById.get(id);
            if (o == null || o.inventoryEffectType() == null || o.inventoryEffectType() == InventoryEffectType.NONE) {
                continue;
            }
            effectOptions.add(o);
        }
        if (effectOptions.isEmpty()) {
            return;
        }

        // Group display order (for canonical ordering within a phase): batch-load only
        // the groups these effect options belong to. The validator already guaranteed
        // every option belongs to an active group allowed for this product.
        Set<UUID> groupIds = new LinkedHashSet<>();
        effectOptions.forEach(o -> groupIds.add(o.groupId()));
        Map<UUID, Integer> groupOrder = new LinkedHashMap<>();
        groups.findAllById(groupIds).forEach(g -> groupOrder.put(g.id(), g.displayOrder()));

        detectConflicts(effectOptions);

        // Canonical order: phase, then group display order, then option display order,
        // then option id — fully independent of the client's request ordering.
        effectOptions.sort(Comparator
                .comparingInt((CustomizationOption o) -> phase(o.inventoryEffectType()))
                .thenComparingInt(o -> groupOrder.getOrDefault(o.groupId(), Integer.MAX_VALUE))
                .thenComparingInt(CustomizationOption::displayOrder)
                .thenComparing(CustomizationOption::id));

        for (CustomizationOption o : effectOptions) {
            int qty = selectionQty.getOrDefault(o.id(), 1);
            UUID src = o.sourceIngredientId();
            UUID tgt = o.targetIngredientId();
            switch (o.inventoryEffectType()) {
                case ADD -> {
                    if (tgt != null && o.effectQuantity() != null && o.effectQuantity().signum() > 0) {
                        addTarget(perUnit, sources, appliedOptions, displayOrders, optionalOnly,
                                tgt, o.effectQuantity().multiply(BigDecimal.valueOf(qty)), o.id(),
                                ConsumptionLineSource.OPTION_ADD);
                    }
                }
                case REMOVE -> {
                    if (src != null && perUnit.containsKey(src)) {
                        BigDecimal current = perUnit.get(src);
                        BigDecimal next = (o.effectQuantity() == null)
                                ? BigDecimal.ZERO
                                : current.subtract(o.effectQuantity().multiply(BigDecimal.valueOf(qty)))
                                        .max(BigDecimal.ZERO);
                        perUnit.put(src, next);
                        tag(sources, appliedOptions, optionalOnly, src, o.id(), ConsumptionLineSource.OPTION_REMOVE);
                    }
                }
                case REPLACE -> {
                    if (src != null && tgt != null && !src.equals(tgt)) {
                        BigDecimal removed = perUnit.getOrDefault(src, BigDecimal.ZERO);
                        BigDecimal add = (o.effectQuantity() != null)
                                ? o.effectQuantity().multiply(BigDecimal.valueOf(qty))
                                : removed;
                        if (perUnit.containsKey(src)) {
                            perUnit.put(src, BigDecimal.ZERO);
                            tag(sources, appliedOptions, optionalOnly, src, o.id(), ConsumptionLineSource.OPTION_REPLACE);
                        }
                        addTarget(perUnit, sources, appliedOptions, displayOrders, optionalOnly,
                                tgt, add, o.id(), ConsumptionLineSource.OPTION_REPLACE);
                    }
                }
                case MULTIPLY -> {
                    if (src != null && o.effectMultiplier() != null && perUnit.containsKey(src)) {
                        BigDecimal current = perUnit.get(src);
                        BigDecimal next = current.multiply(o.effectMultiplier()).setScale(4, RoundingMode.HALF_UP);
                        perUnit.put(src, next);
                        tag(sources, appliedOptions, optionalOnly, src, o.id(), ConsumptionLineSource.OPTION_MULTIPLY);
                    }
                }
                default -> { /* NONE handled above */ }
            }
        }
    }

    @SuppressWarnings("null") // merge(BigDecimal::add) operands are non-null by construction
    private void addTarget(Map<UUID, BigDecimal> perUnit, Map<UUID, EnumSet<ConsumptionLineSource>> sources,
            Map<UUID, LinkedHashSet<UUID>> appliedOptions, Map<UUID, Integer> displayOrders,
            Map<UUID, Boolean> optionalOnly, UUID target, BigDecimal amount, UUID optionId,
            ConsumptionLineSource source) {
        if (amount.signum() <= 0) {
            return;
        }
        perUnit.merge(target, amount, BigDecimal::add);
        displayOrders.putIfAbsent(target, 100_000);
        tag(sources, appliedOptions, optionalOnly, target, optionId, source);
    }

    private void tag(Map<UUID, EnumSet<ConsumptionLineSource>> sources, Map<UUID, LinkedHashSet<UUID>> appliedOptions,
            Map<UUID, Boolean> optionalOnly, UUID id, UUID optionId, ConsumptionLineSource source) {
        sources.computeIfAbsent(id, k -> EnumSet.noneOf(ConsumptionLineSource.class)).add(source);
        appliedOptions.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(optionId);
        optionalOnly.put(id, false);
    }

    private void detectConflicts(List<CustomizationOption> effectOptions) {
        Set<UUID> removeSources = new LinkedHashSet<>();
        Set<UUID> replaceSources = new LinkedHashSet<>();
        Set<UUID> multiplySources = new LinkedHashSet<>();
        for (CustomizationOption o : effectOptions) {
            InventoryEffectType t = o.inventoryEffectType();
            UUID src = o.sourceIngredientId();
            if (t == InventoryEffectType.REPLACE && src != null && src.equals(o.targetIngredientId())) {
                throw conflict();
            }
            if (src == null) {
                continue;
            }
            switch (t) {
                case REMOVE -> {
                    if (!removeSources.add(src)) {
                        throw conflict();
                    }
                }
                case REPLACE -> {
                    if (!replaceSources.add(src)) {
                        throw conflict();
                    }
                }
                case MULTIPLY -> {
                    if (!multiplySources.add(src)) {
                        throw conflict();
                    }
                }
                default -> { /* ADD has no source */ }
            }
        }
        // REMOVE and REPLACE competing for the same source is ambiguous.
        for (UUID replaceSrc : replaceSources) {
            if (removeSources.contains(replaceSrc)) {
                throw conflict();
            }
        }
    }

    private ValidationException conflict() {
        return new ValidationException("CONFLICTING_INVENTORY_EFFECTS",
                "Selected customization options declare conflicting inventory effects");
    }

    private static int phase(InventoryEffectType t) {
        return switch (t) {
            case REMOVE -> 0;
            case REPLACE -> 1;
            case MULTIPLY -> 2;
            case ADD -> 3;
            default -> 4;
        };
    }

    // ------------------------------------------------------------- materialize

    @SuppressWarnings("null") // comparator key extractors are non-null by construction
    private List<CalculatedConsumptionLine> materialize(Map<UUID, BigDecimal> perUnit,
            Map<UUID, EnumSet<ConsumptionLineSource>> sources, Map<UUID, LinkedHashSet<UUID>> appliedOptions,
            Map<UUID, Integer> displayOrders, Map<UUID, Boolean> optionalOnly, BigDecimal productQuantity) {

        Set<UUID> ids = new LinkedHashSet<>(perUnit.keySet());
        Map<UUID, Ingredient> ingredientById = new LinkedHashMap<>();
        ingredients.findAllById(ids).forEach(i -> ingredientById.put(i.id(), i));
        Set<UUID> baseUnitIds = new LinkedHashSet<>();
        ingredientById.values().forEach(i -> baseUnitIds.add(i.baseUnitId()));
        Map<UUID, UnitOfMeasure> unitById = new LinkedHashMap<>();
        units.findAllById(baseUnitIds).forEach(u -> unitById.put(u.id(), u));

        List<CalculatedConsumptionLine> lines = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : perUnit.entrySet()) {
            UUID ingredientId = entry.getKey();
            BigDecimal finalQty = entry.getValue().multiply(productQuantity).setScale(4, RoundingMode.CEILING);
            if (finalQty.signum() <= 0) {
                continue; // zero (or floored-to-zero) demand does not appear in the plan
            }
            if (finalQty.abs().compareTo(MAX_QUANTITY) > 0) {
                throw new ValidationException("CALCULATED_QUANTITY_OVERFLOW",
                        "Calculated demand for an ingredient exceeds the supported maximum");
            }
            Ingredient ingredient = ingredientById.get(ingredientId);
            if (ingredient == null) {
                throw new ValidationException("INGREDIENT_NOT_FOUND",
                        "Unknown ingredient in consumption plan: " + ingredientId);
            }
            UnitOfMeasure baseUnit = unitById.get(ingredient.baseUnitId());
            EnumSet<ConsumptionLineSource> lineSources =
                    sources.getOrDefault(ingredientId, EnumSet.of(ConsumptionLineSource.BASE_RECIPE));
            ConsumptionLineSource source = lineSources.size() == 1
                    ? lineSources.iterator().next()
                    : ConsumptionLineSource.MERGED;
            lines.add(new CalculatedConsumptionLine(
                    ingredientId, ingredient.code(), ingredient.name(), finalQty,
                    baseUnit == null ? null : baseUnit.code(), source,
                    new ArrayList<>(appliedOptions.getOrDefault(ingredientId, new LinkedHashSet<>())),
                    displayOrders.getOrDefault(ingredientId, 0),
                    optionalOnly.getOrDefault(ingredientId, false)));
        }

        lines.sort(Comparator.comparingInt(CalculatedConsumptionLine::displayOrder)
                .thenComparing(CalculatedConsumptionLine::ingredientCode, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CalculatedConsumptionLine::ingredientId));
        return lines;
    }

    private enum SourceHint { NONE_OK, NONE_WITH_WARNING }

    private CalculatedConsumptionPlan emptyPlan(UUID productId, UUID variantId, BigDecimal quantity,
            SourceHint hint, String warning) {
        List<String> warnings = hint == SourceHint.NONE_WITH_WARNING ? List.of(warning) : List.of();
        return new CalculatedConsumptionPlan(productId, variantId, null, null,
                RecipeVersionResolver.Source.NONE, quantity, Instant.now(), List.of(), warnings);
    }
}
