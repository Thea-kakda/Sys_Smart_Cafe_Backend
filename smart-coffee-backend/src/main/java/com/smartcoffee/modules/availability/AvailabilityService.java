package com.smartcoffee.modules.availability;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.availability.InventoryAvailabilityReader.StockSnapshot;
import com.smartcoffee.modules.availability.OptionAvailabilityEvaluator.Evaluation;
import com.smartcoffee.modules.availability.OptionAvailabilityEvaluator.GroupInput;
import com.smartcoffee.modules.availability.OptionAvailabilityEvaluator.OptionInput;
import com.smartcoffee.modules.availability.RecipeAvailabilityCalculator.BaseDemand;
import com.smartcoffee.modules.availability.ServingMath.Servings;
import com.smartcoffee.modules.availability.StoreProductAvailabilityPolicy.PolicyGate;
import com.smartcoffee.modules.catalog.application.EffectiveCustomization;
import com.smartcoffee.modules.catalog.application.EffectiveCustomizationResolver;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.domain.StockMode;
import com.smartcoffee.modules.catalog.domain.StoreProduct;
import com.smartcoffee.modules.catalog.domain.StoreProductId;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.catalog.persistence.StoreProductRepository;
import com.smartcoffee.modules.inventory.persistence.ReconciliationFindingRepository;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Resolved;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single authoritative READ-ONLY availability engine (Slice 3A). It answers, for one
 * Product at one Store, whether it is active / listed / orderable / in stock, which
 * variants and options are available, why anything is unavailable, and — where it can be
 * computed safely — how many base/default servings are possible.
 *
 * <p>It is composed entirely of focused collaborators (spec §15): the store/master policy,
 * the shared recipe resolver + normalizer, the base-serving calculator, the batched
 * inventory reader, and the option evaluator. It performs NO reservation / release /
 * consumption / ledger write and mutates no Product / Recipe / Option / balance / Cart /
 * Order state; every query path is {@code @Transactional(readOnly = true)}.
 *
 * <p>Batch entry {@link #availabilityForProducts} resolves every product's plan first,
 * then loads the store's inventory balances for the UNION of touched ingredients in ONE
 * query, so a catalog page is never a per-product stock-query loop (§16).
 */
@Service
public class AvailabilityService {

    /** Sellable-quantity abuse guard for a single preview. */
    private static final int MAX_QUANTITY = 10_000;

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final StoreProductRepository storeProducts;
    private final EffectiveCustomizationResolver customization;
    private final RecipeVersionResolver resolver;
    private final RecipeAvailabilityCalculator recipeCalculator;
    private final InventoryAvailabilityReader inventoryReader;
    private final OptionAvailabilityEvaluator optionEvaluator;
    private final StoreProductAvailabilityPolicy policy;
    private final ReconciliationFindingRepository reconciliationFindings;
    private final AvailabilityMetrics metrics;
    private final Clock clock;
    private final int complexityCap;

    public AvailabilityService(ProductRepository products, ProductVariantRepository variants,
            StoreProductRepository storeProducts, EffectiveCustomizationResolver customization,
            RecipeVersionResolver resolver,
            RecipeAvailabilityCalculator recipeCalculator, InventoryAvailabilityReader inventoryReader,
            OptionAvailabilityEvaluator optionEvaluator, StoreProductAvailabilityPolicy policy,
            ReconciliationFindingRepository reconciliationFindings, AvailabilityMetrics metrics, Clock clock,
            @Value("${availability.complexity.max-combinations:5000}") int complexityCap) {
        this.products = products;
        this.variants = variants;
        this.storeProducts = storeProducts;
        this.customization = customization;
        this.resolver = resolver;
        this.recipeCalculator = recipeCalculator;
        this.inventoryReader = inventoryReader;
        this.optionEvaluator = optionEvaluator;
        this.policy = policy;
        this.reconciliationFindings = reconciliationFindings;
        this.metrics = metrics;
        this.clock = clock;
        this.complexityCap = Math.max(1, complexityCap);
    }

    /** Request-level availability for a single product (throws 404/400 on bad requests). */
    @Transactional(readOnly = true)
    public ProductAvailabilityResult availabilityForProduct(UUID storeId, UUID productId, int quantity) {
        int q = validateQuantity(quantity);
        long start = System.nanoTime();
        Product product = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
        List<UUID> ids = List.of(productId);
        Map<UUID, StockSnapshot> snapshots = loadSnapshots(storeId, ids, q);
        ProductAvailabilityResult result = computeOne(storeId, product, q, snapshots);
        metrics.recordSingleDuration(System.nanoTime() - start);
        metrics.recordOrderable(result.orderable());
        return result;
    }

    /**
     * Internal BATCH API (spec §19). Bounded, deterministic (result order mirrors a
     * de-duplicated, ascending input), missing products represented safely (a
     * PRODUCT_NOT_FOUND result, never an exception for a batch member), one product's
     * failure cannot corrupt unrelated results, and no per-product stock-query loop.
     */
    @Transactional(readOnly = true)
    public List<ProductAvailabilityResult> availabilityForProducts(UUID storeId, List<UUID> productIds, int quantity) {
        int q = validateQuantity(quantity);
        long start = System.nanoTime();
        Set<UUID> unique = new TreeSet<>(productIds == null ? List.of() : productIds);
        if (unique.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(unique);
        Map<UUID, StockSnapshot> snapshots = loadSnapshots(storeId, ids, q);
        Map<UUID, Product> found = new java.util.HashMap<>();
        products.findAllById(ids).forEach(p -> found.put(p.id(), p));
        List<ProductAvailabilityResult> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Product p = found.get(id);
            if (p == null) {
                out.add(notFoundResult(storeId, id));
                continue;
            }
            try {
                out.add(computeOne(storeId, p, q, snapshots));
            } catch (RuntimeException e) {
                out.add(failedResult(storeId, id));
            }
        }
        metrics.recordBatchDuration(System.nanoTime() - start, ids.size());
        return out;
    }

    // ------------------------------------------------------------------ planning

    /**
     * Loads, in ONE query, the store inventory balances for every ingredient any of the
     * given products could touch (their resolved recipes' lines + their option effects +
     * a small safety margin). This is the N+1 avoidance seam: the loop over products
     * never issues a stock query per product.
     */
    private Map<UUID, StockSnapshot> loadSnapshots(UUID storeId, List<UUID> productIds, int quantity) {
        Set<UUID> ingredients = new TreeSet<>();
        for (UUID productId : productIds) {
            Product product = products.findById(productId).orElse(null);
            if (product == null) {
                continue;
            }
            for (ProductVariant v : plannedConfigurations(product)) {
                UUID variantId = v == null ? null : v.id();
                Resolved r = resolver.resolve(productId, variantId);
                if (r.present()) {
                    ingredients.addAll(recipeCalculator.baseDemand(r.recipeVersion()).ingredients());
                }
            }
            for (GroupInput g : loadGroups(productId)) {
                for (OptionInput o : g.options()) {
                    if (o.sourceIngredientId() != null) {
                        ingredients.add(o.sourceIngredientId());
                    }
                    if (o.targetIngredientId() != null) {
                        ingredients.add(o.targetIngredientId());
                    }
                }
            }
        }
        return inventoryReader.read(storeId, ingredients);
    }

    /**
     * The configurations to price for a product: each non-deleted variant, or a single
     * null-variant (product-level) configuration when the product has no variants.
     */
    private List<ProductVariant> plannedConfigurations(Product product) {
        List<ProductVariant> list = variants.findActiveByProduct(product.id());
        // A product with no variants is priced as ONE product-level configuration.
        // Collections.singletonList (not List.of) because that element is null and
        // List.of forbids nulls.
        return list.isEmpty() ? java.util.Collections.singletonList(null) : list;
    }

    // ------------------------------------------------------------------- compute

    private ProductAvailabilityResult computeOne(UUID storeId, Product product, int quantity,
            Map<UUID, StockSnapshot> snapshots) {
        // Latency is timed once per public entry point (single vs batch), never here.
        StoreProduct storeProduct = storeProducts.findById(new StoreProductId(storeId, product.id())).orElse(null);
        PolicyGate gate = policy.gate(product, storeProduct);

        List<ProductVariant> configurations = plannedConfigurations(product);
        List<GroupInput> groups = loadGroups(product.id());

        List<VariantAvailabilityResult> variantResults = new ArrayList<>();
        List<OptionGroupAvailabilityResult> defaultOptionGroups = List.of();
        Integer defaultBaseServings = null;
        boolean anyInStock = false;
        List<String> productReasons = new ArrayList<>(gate.reasonCodes());
        StringBuilder fingerprintInput = new StringBuilder();
        fingerprintInput.append(storeId).append('|').append(product.id()).append('|')
                .append(product.version()).append('|').append(gate.manualState()).append('|');

        boolean singleConfig = configurations.size() == 1 && configurations.get(0) == null;

        for (ProductVariant variant : configurations) {
            UUID variantId = variant == null ? null : variant.id();
            VariantCompute vc = computeVariant(storeId, product, variant, groups, quantity, snapshots);
            variantResults.add(vc.result());
            if (variant == null || isDefault(configurations, variant)) {
                defaultOptionGroups = vc.optionGroups();
                defaultBaseServings = vc.baseServings();
            }
            if (vc.inStock()) {
                anyInStock = true;
            }
            fingerprintInput.append(variantId).append(':').append(vc.recipeVersionId()).append(',')
                    .append(vc.balanceDigest()).append(';');
        }

        boolean hasVariants = !(singleConfig);
        boolean orderable = gate.orderableIgnoringStock() && anyInStock;
        boolean inStock = anyInStock;
        if (!anyInStock && gate.orderableIgnoringStock()) {
            productReasons.add(AvailabilityReason.PRODUCT_OUT_OF_STOCK.code());
        }

        // Variant-level detail is only meaningful when the product actually has variants.
        List<VariantAvailabilityResult> variantsOut = hasVariants ? variantResults : List.of();
        if (!hasVariants && !variantResults.isEmpty()) {
            // The single null-variant computation still contributed stock verdicts; fold it.
            productReasons.addAll(variantResults.get(0).reasonCodes());
        }

        ProductAvailabilityResult result = new ProductAvailabilityResult(
                product.id(), storeId, gate.active(), gate.listed(), orderable, inStock, gate.manualState(),
                defaultBaseServings, distinct(productReasons), variantsOut, defaultOptionGroups,
                clock.instant(), digest(fingerprintInput.toString()));

        return result;
    }

    private record VariantCompute(VariantAvailabilityResult result, List<OptionGroupAvailabilityResult> optionGroups,
            Integer baseServings, boolean inStock, UUID recipeVersionId, long balanceDigest) {
    }

    private VariantCompute computeVariant(UUID storeId, Product product, ProductVariant variant,
            List<GroupInput> groups, int quantity, Map<UUID, StockSnapshot> snapshots) {
        UUID variantId = variant == null ? null : variant.id();
        boolean variantActive = variant == null || variant.active();
        List<String> reasons = new ArrayList<>();

        if (variant != null && !variantActive) {
            reasons.add(AvailabilityReason.VARIANT_INACTIVE.code());
            return new VariantCompute(new VariantAvailabilityResult(variantId, variant.name(), false, false, false,
                    null, RecipeVersionResolver.Source.NONE.name(), null, reasons), List.of(), null, false, null, 0L);
        }

        StockMode mode = product.stockMode();
        Resolved resolved = resolver.resolve(product.id(), variantId);
        RecipeVersion version = resolved.present() ? resolved.recipeVersion() : null;

        boolean tracked = mode == StockMode.MADE_TO_ORDER || mode == StockMode.RECIPE_BASED;

        // Un-tracked modes: inventory never blocks (§6). Preserve orderability, no servings.
        if (!tracked) {
            Integer servings = null; // nullable: intentionally not inventory-tracked
            // ACTIVE_RECIPE_REQUIRED only guards RECIPE_BASED; other modes keep legacy behavior.
            return new VariantCompute(new VariantAvailabilityResult(variantId, variantName(variant), true, true, true,
                    servings, resolved.source().name(), versionId(version), List.of()), List.of(), servings, true,
                    versionId(version), 0L);
        }

        if (version == null) {
            if (mode == StockMode.RECIPE_BASED) {
                reasons.add(AvailabilityReason.ACTIVE_RECIPE_REQUIRED.code());
                return new VariantCompute(new VariantAvailabilityResult(variantId, variantName(variant), true, false,
                        false, null, resolved.source().name(), null, reasons), List.of(), null, false, null, 0L);
            }
            // MADE_TO_ORDER legacy with no recipe: preserve legacy orderable, servings nullable.
            return new VariantCompute(new VariantAvailabilityResult(variantId, variantName(variant), true, true, true,
                    null, resolved.source().name(), null, List.of()), List.of(), null, true, null, 0L);
        }

        BaseDemand base = recipeCalculator.baseDemand(version);
        Map<UUID, BigDecimal> required = base.requiredPerServing();
        Servings baseServingsResult = ServingMath.compute(required, snapshots);
        Integer baseServings = baseServingsResult.limited() ? baseServingsResult.servings() : null;

        // Reconciliation safety signal (§14): any required ingredient under an OPEN
        // store-scoped CRITICAL finding makes this configuration conservatively
        // unavailable with a safe reason (no anomaly internals exposed).
        Set<UUID> criticalIngredients = criticalAnomalies(storeId, required.keySet());

        Evaluation eval = optionEvaluator.evaluate(required, snapshots, groups, quantity, complexityCap);
        if (eval.complexityExceeded()) {
            reasons.add(AvailabilityReason.AVAILABILITY_COMPLEXITY_LIMIT.code());
            metrics.recordComplexityLimit();
        }

        boolean configInStock = eval.configurationInStock() && !baseServingsResult.anomaly();
        if (baseServingsResult.anomaly()) {
            reasons.add(AvailabilityReason.INVENTORY_DATA_INCONSISTENT.code());
            metrics.recordFailed();
        }
        boolean affectedByCritical = required.keySet().stream().anyMatch(criticalIngredients::contains);
        if (affectedByCritical) {
            configInStock = false;
            reasons.add(AvailabilityReason.RECONCILIATION_CRITICAL.code());
        }
        if (!configInStock && reasons.isEmpty()) {
            reasons.add(AvailabilityReason.VARIANT_OUT_OF_STOCK.code());
        }

        boolean orderable = configInStock;
        boolean requiredGroupSatisfied = eval.perGroup().stream()
                .noneMatch(g -> g.required() && !g.atLeastOneValidSelection());
        if (!requiredGroupSatisfied && !reasons.contains(AvailabilityReason.RECONCILIATION_CRITICAL.code())
                && !reasons.contains(AvailabilityReason.AVAILABILITY_COMPLEXITY_LIMIT.code())) {
            reasons.add(AvailabilityReason.REQUIRED_OPTION_GROUP_UNAVAILABLE.code());
        }

        long balanceDigest = balanceDigest(snapshots, required.keySet());
        VariantAvailabilityResult vr = new VariantAvailabilityResult(variantId, variantName(variant), true, orderable,
                configInStock, baseServings, resolved.source().name(), versionId(version), distinct(reasons));
        return new VariantCompute(vr, eval.perGroup(), baseServings, configInStock, versionId(version), balanceDigest);
    }

    // ------------------------------------------------------------------- loading

    private List<GroupInput> loadGroups(UUID productId) {
        List<GroupInput> out = new ArrayList<>();
        for (EffectiveCustomization.Group g : customization.resolve(productId)) {
            List<OptionInput> opts = new ArrayList<>();
            for (EffectiveCustomization.Option o : g.options()) {
                opts.add(new OptionInput(o.optionId(), o.name(), o.effectivePrice(), o.masterActive(),
                        o.effectType(), o.sourceIngredientId(), o.targetIngredientId(), o.effectQuantity(),
                        o.effectMultiplier(), o.displayOrder()));
            }
            out.add(new GroupInput(g.groupId(), g.name(), g.selectionType(), g.minimumSelections(),
                    g.maximumSelections(), opts));
        }
        return out;
    }

    private Set<UUID> criticalAnomalies(UUID storeId, Set<UUID> ingredients) {
        if (ingredients.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(reconciliationFindings.criticalAnomalousIngredients(storeId, ingredients));
    }

    // ------------------------------------------------------------------- helpers

    private boolean isDefault(List<ProductVariant> configurations, ProductVariant variant) {
        if (configurations.size() == 1) {
            return true;
        }
        return variant.isDefault();
    }

    private static String variantName(ProductVariant v) {
        return v == null ? null : v.name();
    }

    private static UUID versionId(RecipeVersion v) {
        return v == null ? null : v.id();
    }

    private int validateQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new ValidationException("INVALID_AVAILABILITY_QUANTITY",
                    "Availability quantity must be between 1 and " + MAX_QUANTITY);
        }
        return quantity;
    }

    private long balanceDigest(Map<UUID, StockSnapshot> snapshots, Set<UUID> ingredients) {
        long acc = 1469598103934665603L;
        for (UUID id : new TreeSet<>(ingredients)) {
            StockSnapshot s = snapshots.get(id);
            long v = s == null ? -1L : s.version();
            acc = acc * 1099511628211L + v;
        }
        return acc;
    }

    private static List<String> distinct(List<String> codes) {
        return new ArrayList<>(new LinkedHashSet<>(codes));
    }

    private static String digest(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private ProductAvailabilityResult notFoundResult(UUID storeId, UUID productId) {
        return new ProductAvailabilityResult(productId, storeId, false, false, false, false,
                ManualAvailabilityState.AUTO, null, List.of(AvailabilityReason.PRODUCT_NOT_FOUND.code()), List.of(),
                List.of(), clock.instant(), null);
    }

    private ProductAvailabilityResult failedResult(UUID storeId, UUID productId) {
        return new ProductAvailabilityResult(productId, storeId, false, false, false, false,
                ManualAvailabilityState.AUTO, null, List.of(AvailabilityReason.AVAILABILITY_CALCULATION_FAILED.code()),
                List.of(), List.of(), clock.instant(), null);
    }
}
