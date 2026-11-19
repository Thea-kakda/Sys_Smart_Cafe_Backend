package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.availability.AvailabilityService;
import com.smartcoffee.modules.availability.ManualAvailabilityState;
import com.smartcoffee.modules.availability.OptionAvailabilityResult;
import com.smartcoffee.modules.availability.OptionGroupAvailabilityResult;
import com.smartcoffee.modules.availability.ProductAvailabilityResult;
import com.smartcoffee.modules.availability.VariantAvailabilityResult;
import com.smartcoffee.modules.catalog.api.CatalogOptionGroupResponse;
import com.smartcoffee.modules.catalog.api.CatalogOptionValueResponse;
import com.smartcoffee.modules.catalog.api.CatalogVariantResponse;
import com.smartcoffee.modules.catalog.api.CustomizationGroupResponse;
import com.smartcoffee.modules.catalog.api.ProductDetailsResponse;
import com.smartcoffee.modules.catalog.api.ProductSummaryResponse;
import com.smartcoffee.modules.catalog.domain.Channel;
import com.smartcoffee.modules.catalog.domain.ProductChannel;
import com.smartcoffee.modules.catalog.domain.ProductChannelSchedule;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.domain.StoreProductId;
import com.smartcoffee.modules.catalog.persistence.ChannelCatalogProjection;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import com.smartcoffee.modules.catalog.persistence.ProductChannelRepository;
import com.smartcoffee.modules.catalog.persistence.ProductChannelScheduleRepository;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.catalog.persistence.StoreProductRepository;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SINGLE shared channel-catalog projector (Slice 3B, spec §10). Every sellable
 * surface (POS, Customer Web, Customer App) and the management preview go through
 * here, so availability semantics can never diverge between list and detail.
 *
 * <p>Composition (spec §5): the final verdict is
 * {@code channelOrderable = channelPolicyOrderable AND availabilityResult.orderable},
 * where the channel policy (publication visible/orderable + publication window +
 * weekly schedule) is computed here and the inventory/variant/option verdict comes
 * from the READ-ONLY Slice 3A {@link AvailabilityService}. Availability is NEVER
 * recalculated in this layer.
 *
 * <p>Performance (spec §6/§13): exactly ONE visibility-filtered, channel-display-
 * ordered, paginated query resolves a page's membership (hidden products are removed
 * BEFORE pagination), then ONE batch availability call covers the whole page, with a
 * bounded schedule/channel batch only when explicit channel rows exist. No per-product
 * availability loop, no post-pagination filtering.
 */
@Service
public class ChannelCatalogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_QUANTITY = 1;

    private final StoreProductRepository storeProducts;
    private final ProductChannelRepository productChannels;
    private final ProductChannelScheduleRepository schedules;
    private final ProductVariantRepository variants;
    private final ProductRepository products;
    private final ProductCategoryRepository categories;
    private final StoreRepository stores;
    private final AvailabilityService availability;
    private final ProductCustomizationQueryService customizations;
    private final CatalogImageUrlResolver images;
    private final Clock clock;

    public ChannelCatalogService(StoreProductRepository storeProducts, ProductChannelRepository productChannels,
            ProductChannelScheduleRepository schedules, ProductVariantRepository variants, ProductRepository products,
            ProductCategoryRepository categories, StoreRepository stores,
            AvailabilityService availability, ProductCustomizationQueryService customizations,
            CatalogImageUrlResolver images, Clock clock) {
        this.storeProducts = storeProducts;
        this.productChannels = productChannels;
        this.schedules = schedules;
        this.variants = variants;
        this.products = products;
        this.categories = categories;
        this.stores = stores;
        this.availability = availability;
        this.customizations = customizations;
        this.images = images;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ LIST

    @Transactional(readOnly = true)
    public Page<ProductSummaryResponse> list(UUID storeId, Channel channel, String search, UUID categoryId,
            boolean availableOnly, int page, int size) {
        var store = requireStore(storeId);
        ZoneId storeZone = ZoneId.of(store.timezone());
        Instant now = clock.instant();

        boolean inherit = channel.inheritsLegacyVisibility();
        Page<ChannelCatalogProjection> raw = storeProducts.channelCatalog(storeId, channel.name(), inherit, inherit,
                search == null ? "" : search.trim(), categoryId, availableOnly,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE)));

        List<UUID> productIds = raw.getContent().stream().map(row -> row.getProductId()).distinct()
                .collect(Collectors.toList());
        // EXACTLY ONE batch availability call for the whole page (spec §6/§13).
        Map<UUID, ProductAvailabilityResult> availById = availability.availabilityForProducts(storeId, productIds,
                DEFAULT_QUANTITY).stream().collect(Collectors.toMap(result -> result.productId(), result -> result));

        ChannelPolicy policy = loadChannelPolicy(raw.getContent().stream().map(row -> row.getChannelId())
                .filter(java.util.Objects::nonNull).collect(Collectors.toList()), storeZone, now);

        return raw.map(p -> toSummary(p, availById.get(p.getProductId()), policy));
    }

    private ProductSummaryResponse toSummary(ChannelCatalogProjection p, ProductAvailabilityResult a,
            ChannelPolicy policy) {
        boolean visible = p.getChannelVisible();
        List<String> reasons = new ArrayList<>();
        boolean channelGate = p.getChannelOrderable();
        if (!channelGate) {
            reasons.add(CatalogChannelReason.CHANNEL_NOT_ORDERABLE.code());
        }
        var win = policy.window(p.getChannelId());
        if (!win.withinWindow()) {
            reasons.add(CatalogChannelReason.CHANNEL_OUTSIDE_PUBLICATION_WINDOW.code());
        }
        if (!win.scheduleOpen()) {
            reasons.add(CatalogChannelReason.CHANNEL_OUTSIDE_SCHEDULE.code());
        }
        boolean inStock = a != null && a.inStock();
        boolean availOrderable = a != null && a.orderable();
        if (a != null) {
            reasons.addAll(a.reasonCodes());
        }
        boolean orderable = visible && channelGate && win.withinWindow() && win.scheduleOpen() && availOrderable;
        String manualState = a != null ? a.manualState().name() : ManualAvailabilityState.AUTO.name();

        return new ProductSummaryResponse(p.getProductId(), p.getProductCode(), p.getCategoryId(),
                p.getCategoryName(), displayName(p), override(p.getChannelDisplayDescription(), p.getDescription()),
                images.resolve(p.getImageObjectKey()),
                p.getPrice(), p.getCurrencyCode(), p.getEffectiveAvailable(), p.getPreparationMinutes(),
                p.getCustomizable(), visible, orderable, inStock, manualState, dedupe(reasons));
    }

    // ---------------------------------------------------------------- DETAIL

    @Transactional(readOnly = true)
    public ProductDetailsResponse detail(UUID storeId, Channel channel, UUID productId) {
        return detail(storeId, channel, productId, true);
    }

    /**
     * Channel-aware product detail. When {@code requireVisible} is true a product not
     * visible on the channel is a 404 (public catalog rule); the management preview
     * passes false so operators can still retrieve hidden products for diagnostics
     * (spec §4B).
     */
    @Transactional(readOnly = true)
    public ProductDetailsResponse detail(UUID storeId, Channel channel, UUID productId, boolean requireVisible) {
        var store = requireStore(storeId);
        ZoneId storeZone = ZoneId.of(store.timezone());
        Instant now = clock.instant();

        ProductAvailabilityResult a = availability.availabilityForProduct(storeId, productId, DEFAULT_QUANTITY);

        var opt = productChannels.findByStoreIdAndProductIdAndChannel(storeId, productId, channel);
        boolean inherit = channel.inheritsLegacyVisibility();
        boolean visible = opt.map(channelRow -> channelRow.visible()).orElse(inherit);
        boolean channelGate = opt.map(channelRow -> channelRow.orderable()).orElse(inherit);
        UUID channelId = opt.map(channelRow -> channelRow.id()).orElse(null);

        boolean withinWindow = true;
        boolean scheduleOpen = true;
        if (channelId != null) {
            var ch = opt.get();
            ZoneId zone = ch.timezone() != null ? ZoneId.of(ch.timezone()) : storeZone;
            withinWindow = (ch.availableFrom() == null || !now.isBefore(ch.availableFrom()))
                    && (ch.availableUntil() == null || now.isBefore(ch.availableUntil()));
            List<ProductChannelSchedule> sched = schedules
                    .findByProductChannelIdOrderByDayOfWeekAscStartLocalTimeAsc(channelId);
            scheduleOpen = ChannelScheduleEvaluator.isOpenNow(sched, zone, now);
        }

        if (!visible && requireVisible) {
            // A hidden channel product is not part of this surface's catalog; the
            // management preview reads hidden products through its own path.
            throw new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product is not available on this channel");
        }

        List<String> reasons = new ArrayList<>();
        if (!channelGate) {
            reasons.add(CatalogChannelReason.CHANNEL_NOT_ORDERABLE.code());
        }
        if (!withinWindow) {
            reasons.add(CatalogChannelReason.CHANNEL_OUTSIDE_PUBLICATION_WINDOW.code());
        }
        if (!scheduleOpen) {
            reasons.add(CatalogChannelReason.CHANNEL_OUTSIDE_SCHEDULE.code());
        }
        reasons.addAll(a.reasonCodes());
        boolean orderable = channelGate && withinWindow && scheduleOpen && a.orderable();

        var product = legacyFields(storeId, productId); // reuse master + price + presentation
        List<CatalogVariantResponse> variantResponses = projectVariants(productId, a);
        List<CatalogOptionGroupResponse> optionResponses = projectOptions(productId, a);

        String dispName = override(opt.map(channelRow -> channelRow.displayName()).orElse(null), product.name());
        String dispDesc = override(opt.map(channelRow -> channelRow.displayDescription()).orElse(null),
                product.description());

        return new ProductDetailsResponse(product.productId(), product.productCode(), product.categoryId(),
                product.categoryName(), dispName, dispDesc, product.imageUrl(), product.price(),
                product.currencyCode(), product.available(), product.preparationMinutes(),
                product.customizationGroups(), visible, orderable, a.inStock(), a.manualState().name(),
                dedupe(reasons), variantResponses, optionResponses);
    }

    // --------------------------------------------------------------- projection

    private List<CatalogVariantResponse> projectVariants(UUID productId, ProductAvailabilityResult a) {
        Map<UUID, VariantAvailabilityResult> byId = a.variants().stream()
                .collect(Collectors.toMap(variant -> variant.variantId(), v -> v, (x, y) -> x));
        List<CatalogVariantResponse> out = new ArrayList<>();
        for (ProductVariant v : variants.findActiveByProduct(productId)) {
            VariantAvailabilityResult vr = byId.get(v.id());
            boolean vOrderable = vr != null && vr.orderable();
            boolean vInStock = vr != null && vr.inStock();
            List<String> vReasons = vr != null ? new ArrayList<>(vr.reasonCodes()) : List.of();
            out.add(new CatalogVariantResponse(v.id(), v.code(), v.name(), v.price(), v.currencyCode(), v.active(),
                    vOrderable, vInStock, dedupe(vReasons)));
        }
        return out;
    }

    private List<CatalogOptionGroupResponse> projectOptions(UUID productId, ProductAvailabilityResult a) {
        Map<UUID, OptionGroupAvailabilityResult> byGroup = a.optionGroups().stream()
                .collect(Collectors.toMap(group -> group.groupId(), g -> g, (x, y) -> x));
        List<CatalogOptionGroupResponse> out = new ArrayList<>();
        for (CustomizationGroupResponse g : customizations.forProduct(productId)) {
            OptionGroupAvailabilityResult ar = byGroup.get(g.groupId());
            Map<UUID, OptionAvailabilityResult> valById = ar == null ? Map.of()
                    : ar.values().stream().collect(Collectors.toMap(option -> option.optionId(), o -> o,
                            (x, y) -> x));
            boolean atLeastOne = ar != null && ar.atLeastOneValidSelection();
            List<CatalogOptionValueResponse> values = g.options().stream().map(o -> {
                OptionAvailabilityResult or = valById.get(o.optionId());
                boolean selectable = or == null ? o.available() : or.selectable();
                boolean available = or == null ? o.available() : or.available();
                List<String> reasons = or == null ? List.of() : new ArrayList<>(or.reasonCodes());
                return new CatalogOptionValueResponse(o.optionId(), o.code(), o.name(), o.additionalPrice(), selectable,
                        available, dedupe(reasons));
            }).collect(Collectors.toList());
            out.add(new CatalogOptionGroupResponse(g.groupId(), g.code(), g.name(), g.required(), g.selectionType(),
                    g.minimumSelections(), g.maximumSelections(), atLeastOne, values));
        }
        return out;
    }

    // ------------------------------------------------------------------ policy

    /** Batch channel schedule + publication-window verdicts for the page's channel ids. */
    private ChannelPolicy loadChannelPolicy(List<UUID> channelIds, ZoneId storeZone, Instant now) {
        List<UUID> ids = channelIds.stream().distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return ChannelPolicy.EMPTY;
        }
        Map<UUID, ProductChannel> channelById = productChannels.findAllById(ids).stream()
                .collect(Collectors.toMap(channel -> channel.id(), Function.identity()));
        Map<UUID, List<ProductChannelSchedule>> schedById = schedules.findByProductChannelIdIn(ids).stream()
                .collect(Collectors.groupingBy(schedule -> schedule.productChannelId()));
        Map<UUID, Window> computed = new java.util.HashMap<>();
        for (UUID id : ids) {
            ProductChannel ch = channelById.get(id);
            if (ch == null) {
                continue;
            }
            ZoneId zone = ch.timezone() != null ? ZoneId.of(ch.timezone()) : storeZone;
            boolean withinWindow = (ch.availableFrom() == null || !now.isBefore(ch.availableFrom()))
                    && (ch.availableUntil() == null || now.isBefore(ch.availableUntil()));
            boolean open = ChannelScheduleEvaluator.isOpenNow(schedById.getOrDefault(id, List.of()), zone, now);
            computed.put(id, new Window(withinWindow, open));
        }
        return new ChannelPolicy(computed);
    }

    private record Window(boolean withinWindow, boolean scheduleOpen) {
    }

    private static final class ChannelPolicy {
        static final ChannelPolicy EMPTY = new ChannelPolicy(Map.of());
        private final Map<UUID, Window> byId;

        ChannelPolicy(Map<UUID, Window> byId) {
            this.byId = byId;
        }

        Window window(UUID channelId) {
            // No explicit channel row -> inherited: always within window and open.
            return channelId == null ? new Window(true, true)
                    : byId.getOrDefault(channelId, new Window(true, true));
        }
    }

    // ----------------------------------------------------------------- helpers

    private com.smartcoffee.modules.organization.domain.Store requireStore(UUID storeId) {
        return stores.findByIdAndActiveTrue(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "Store not found"));
    }

    private static String displayName(ChannelCatalogProjection p) {
        String override = p.getChannelDisplayName();
        return (override != null && !override.isBlank()) ? override : p.getProductName();
    }

    /** Returns the channel override when non-blank, else the master fallback. */
    private static String override(String channelOverride, String fallback) {
        return (channelOverride != null && !channelOverride.isBlank()) ? channelOverride : fallback;
    }

    /** Deterministic, order-preserving, null-free reason collection. */
    private static List<String> dedupe(List<String> reasons) {
        var set = new LinkedHashSet<String>();
        for (String r : reasons) {
            if (r != null && !r.isBlank()) {
                set.add(r);
            }
        }
        return List.copyOf(set);
    }

    // Legacy master/price/presentation reused for the detail envelope so the two
    // catalog surfaces (this and the management preview) never disagree on identity.
    private LegacyProduct legacyFields(UUID storeId, UUID productId) {
        var product = products.findByIdAndActiveTrue(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
        var storeProduct = storeProducts.findById(new StoreProductId(storeId, productId))
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND",
                        "Product is not offered by this store"));
        var category = categories.findByIdAndActiveTrue(product.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "Category not found"));
        var price = storeProduct.overridePrice() == null ? product.basePrice() : storeProduct.overridePrice();
        return new LegacyProduct(product.id(), product.code(), category.id(), category.name(), product.name(),
                product.description(), images.resolve(product.imageObjectKey()), price, product.currencyCode(),
                storeProduct.available(), product.preparationMinutes(), customizations.forProduct(productId));
    }

    /** Immutable legacy detail fields resolved straight from catalog master + store product. */
    public record LegacyProduct(UUID productId, String productCode, UUID categoryId, String categoryName, String name,
            String description, String imageUrl, java.math.BigDecimal price, String currencyCode, boolean available,
            int preparationMinutes, List<CustomizationGroupResponse> customizationGroups) {
    }
}
