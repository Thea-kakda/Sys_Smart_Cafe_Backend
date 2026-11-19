package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.catalog.api.CreateStoreProductRequest;
import com.smartcoffee.modules.catalog.api.ManagementStoreProductResponse;
import com.smartcoffee.modules.catalog.api.UpdateStoreProductConfigurationRequest;
import com.smartcoffee.modules.catalog.domain.StoreProduct;
import com.smartcoffee.modules.catalog.domain.StoreProductId;
import com.smartcoffee.modules.catalog.persistence.ManagementStoreProductProjection;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.StoreProductRepository;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import com.smartcoffee.modules.production.persistence.ProductionStationRepository;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.audit.application.AuditWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protected branch-product (catalog.store_products) administration for
 * Checkpoints 3A/3B. This service is the single backend authority for the
 * management view: prices, availability, sold-out windows, production station
 * assignment and optimistic-lock versions all come from database state, never
 * from client calculations.
 *
 * <p>Invariants enforced server-side:
 * <ul>
 * <li>effective price = override when non-null, else base price (SQL coalesce,
 * identical expression to the public catalog projection — no second
 * implementation);</li>
 * <li>effective availability = branch flag AND no active sold-out window
 * (verified existing rule, reused, not redefined);</li>
 * <li>new/updated configurations require a station belonging to THIS branch
 * that exists and is active — the legacy routing fallback can never be fed by
 * fresh management writes;</li>
 * <li>updates echo storeProductVersion or map to 409 VERSION_CONFLICT;
 * concurrent duplicate creates map to 409 CONFIGURATION_EXISTS via the
 * primary-key violation, so the pre-check race is safe;</li>
 * <li>every mutation writes audit records with before/after values.</li>
 * </ul>
 */
@Service
public class StoreProductManagementService {

    /** Filter vocabulary; anything else is rejected instead of silently ignored. */
    private static final Set<String> AVAILABILITY_FILTERS = Set.of("AVAILABLE", "UNAVAILABLE");
    private static final Set<String> OVERRIDE_FILTERS = Set.of("INHERITED", "OVERRIDE");
    /** Whitelisted sort aliases of the management projection (SQL-injection safe). */
    private static final Map<String, String> SORTABLE = Map.ofEntries(
            Map.entry("productName", "productName"), Map.entry("productCode", "productCode"),
            Map.entry("basePrice", "basePrice"), Map.entry("overridePrice", "overridePrice"),
            Map.entry("effectivePrice", "effectivePrice"), Map.entry("updatedAt", "updatedAt"),
            Map.entry("productActive", "productActive"), Map.entry("branchAvailable", "branchAvailable"),
            Map.entry("effectiveAvailable", "effectiveAvailable"), Map.entry("stationCode", "stationCode"),
            Map.entry("preparationMinutes", "preparationMinutes"));

    private final StoreProductRepository storeProducts;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final ProductionStationRepository stations;
    private final AuditWriter audit;

    public StoreProductManagementService(StoreProductRepository storeProducts, ProductRepository products,
            StoreRepository stores, ProductionStationRepository stations, AuditWriter audit) {
        this.storeProducts = storeProducts;
        this.products = products;
        this.stores = stores;
        this.stations = stations;
        this.audit = audit;
    }

    // ------------------------------------------------------------- 3A read

    @Transactional(readOnly = true)
    public Page<ManagementStoreProductResponse> list(CurrentActor actor, UUID storeId, String search,
            UUID categoryId, Boolean productActive, Boolean branchAvailable, String availability,
            String overrideState, String stationCode, String sort, String direction, int page, int size) {
        requireStoreAccess(actor, storeId);
        String q = (search == null || search.isBlank()) ? "" : search.trim();
        String avail = filter(availability, AVAILABILITY_FILTERS, "INVALID_AVAILABILITY_FILTER");
        String override = filter(overrideState, OVERRIDE_FILTERS, "INVALID_OVERRIDE_FILTER");
        String station = stationFilter(stationCode);
        return storeProducts.management(storeId, q, categoryId, productActive, branchAvailable, override, avail,
                station, pageable(sort, direction, page, size)).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ManagementStoreProductResponse get(CurrentActor actor, UUID storeId, UUID productId) {
        requireStoreAccess(actor, storeId);
        return authoritative(storeId, productId);
    }

    // --------------------------------------------------------- 3B mutations

    @Transactional
    public ManagementStoreProductResponse add(CurrentActor actor, UUID storeId, CreateStoreProductRequest req) {
        requireStoreAccess(actor, storeId);
        var product = products.findById(req.productId())
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
        requireCurrencyAlignment(storeId, product.currencyCode());

        if (storeProducts.findById(new StoreProductId(storeId, req.productId())).isPresent()) {
            throw new ConflictException("CONFIGURATION_EXISTS", "This branch already carries the product");
        }
        validateOverridePrice(req.overridePrice());
        String station = requireActiveStoreStation(storeId, req.stationCode());

        StoreProduct created = StoreProduct.create(storeId, req.productId(), station, req.overridePrice(),
                !Boolean.FALSE.equals(req.available()), req.soldOutUntil());
        try {
            storeProducts.saveAndFlush(created);
        } catch (DataIntegrityViolationException concurrentCreate) {
            // Lost the create race against the (store_id, product_id) primary key.
            throw new ConflictException("CONFIGURATION_EXISTS",
                    "This branch already carries the product (concurrent create)");
        }

        ManagementStoreProductResponse after = authoritative(storeId, req.productId());
        audit.success(actor.userId(), storeId, "BRANCH_PRODUCT_ADDED", "CATALOG", "STORE_PRODUCT", req.productId(),
                null, after, "Added to branch " + storeId);
        return after;
    }

    @Transactional
    public ManagementStoreProductResponse updateConfiguration(CurrentActor actor, UUID storeId, UUID productId,
            UpdateStoreProductConfigurationRequest req) {
        requireStoreAccess(actor, storeId);
        StoreProduct sp = storeProducts.findById(new StoreProductId(storeId, productId))
                .orElseThrow(() -> new ResourceNotFoundException("STORE_PRODUCT_NOT_FOUND",
                        "Product is not configured for this branch"));
        if (!Objects.equals(req.storeProductVersion(), sp.version())) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This branch product was updated by another user. Refresh before editing again.");
        }
        validateOverridePrice(req.overridePrice());
        String station = requireActiveStoreStation(storeId, req.stationCode());

        ManagementStoreProductResponse before = authoritative(storeId, productId);
        sp.reconfigure(station, req.overridePrice(), req.available(), req.soldOutUntil());
        storeProducts.saveAndFlush(sp);
        ManagementStoreProductResponse after = authoritative(storeId, productId);

        // Field-level audit actions required by the checkpoint contract.
        if (!samePrice(before.overridePrice(), after.overridePrice())) {
            boolean cleared = before.overridePrice() != null && after.overridePrice() == null;
            auditField(actor, storeId, productId, cleared ? "BRANCH_PRODUCT_OVERRIDE_CLEARED"
                    : "BRANCH_PRODUCT_PRICING_UPDATED", before, after, cleared
                            ? "Branch override cleared; price inherited from base"
                            : "Override price " + before.overridePrice() + " -> " + after.overridePrice());
        }
        if (before.branchAvailable() != after.branchAvailable()) {
            auditField(actor, storeId, productId, "BRANCH_PRODUCT_AVAILABILITY_UPDATED", before, after,
                    "Branch availability " + before.branchAvailable() + " -> " + after.branchAvailable());
        }
        if (!sameInstant(before.soldOutUntil(), after.soldOutUntil())) {
            auditField(actor, storeId, productId, "BRANCH_PRODUCT_SOLD_OUT_UPDATED", before, after,
                    "Sold-out until " + before.soldOutUntil() + " -> " + after.soldOutUntil());
        }
        if (!Objects.equals(before.stationCode(), after.stationCode())) {
            auditField(actor, storeId, productId, "BRANCH_PRODUCT_STATION_UPDATED", before, after,
                    "Production station " + before.stationCode() + " -> " + after.stationCode());
        }
        return after;
    }

    // ------------------------------------------------------------- guards

    private void requireStoreAccess(CurrentActor actor, UUID storeId) {
        stores.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "Branch not found"));
        if (!actor.canAccessStore(storeId)) {
            throw new ForbiddenException("STORE_ACCESS_DENIED", "No access to this branch");
        }
    }

    /**
     * Station must exist FOR THIS BRANCH and be active. A code owned by another
     * branch resolves to STATION_NOT_FOUND because the lookup is store-scoped:
     * cross-branch station references can never be persisted.
     */
    private String requireActiveStoreStation(UUID storeId, String stationCode) {
        String code = stationCode == null ? "" : stationCode.trim();
        if (code.isEmpty()) {
            throw new ValidationException("STATION_REQUIRED", "A production station is required");
        }
        var station = stations.findByStoreIdAndCode(storeId, code)
                .orElseThrow(() -> new ValidationException("STATION_NOT_FOUND",
                        "Station does not exist for this branch"));
        if (!station.active()) {
            throw new ValidationException("STATION_INACTIVE", "Station is inactive");
        }
        return code;
    }

    private void requireCurrencyAlignment(UUID storeId, String productCurrency) {
        // Store currency rules stay authoritative: a branch cannot carry a product
        // priced in a currency its carts and payments do not settle in.
        var store = stores.findById(storeId).orElseThrow();
        if (!Objects.equals(store.defaultCurrency(), productCurrency)) {
            throw new ValidationException("CURRENCY_MISMATCH", "Product currency does not match branch currency");
        }
    }

    private void validateOverridePrice(BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            throw new ValidationException("NEGATIVE_PRICE", "Override price must not be negative");
        }
        if (value.scale() > 4 || value.precision() > 14) {
            throw new ValidationException("PRICE_SCALE_EXCEEDED",
                    "Override price exceeds supported precision numeric(14,4)");
        }
    }

    // ------------------------------------------------------------- helpers

    private ManagementStoreProductResponse authoritative(UUID storeId, UUID productId) {
        return storeProducts.managementOne(storeId, productId).map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_PRODUCT_NOT_FOUND",
                        "Product is not configured for this branch"));
    }

    private ManagementStoreProductResponse toResponse(ManagementStoreProductProjection p) {
        return new ManagementStoreProductResponse(p.getProductId(), p.getProductCode(), p.getProductName(),
                p.getCategoryId(), p.getCategoryName(), p.getBasePrice(), p.getOverridePrice(),
                p.getOverridePrice() == null, p.getEffectivePrice(), p.getCurrencyCode(),
                truth(p.getProductActive()), truth(p.getBranchAvailable()), truth(p.getEffectiveAvailable()),
                p.getSoldOutUntil(), p.getStationCode(), p.getPreparationMinutes() == null ? 0
                        : p.getPreparationMinutes(),
                truth(p.getCustomizable()), p.getProductVersion() == null ? 0L : p.getProductVersion(),
                p.getStoreProductVersion() == null ? 0L : p.getStoreProductVersion(), p.getUpdatedAt());
    }

    private static boolean truth(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static boolean samePrice(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    /** DB round-trips store microseconds; compare at that resolution to avoid phantom audits. */
    private static boolean sameInstant(Instant a, Instant b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.truncatedTo(ChronoUnit.MICROS).equals(b.truncatedTo(ChronoUnit.MICROS));
    }

    private void auditField(CurrentActor actor, UUID storeId, UUID productId, String action,
            ManagementStoreProductResponse before, ManagementStoreProductResponse after, String reason) {
        audit.success(actor.userId(), storeId, action, "CATALOG", "STORE_PRODUCT", productId, before, after, reason);
    }

    private static String filter(String value, Set<String> allowed, String errorCode) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        String upper = value.trim().toUpperCase();
        if (!allowed.contains(upper)) {
            throw new ValidationException(errorCode, "Unsupported filter value: " + value);
        }
        return upper;
    }

    /** stationCode filter: null/omitted = all, literal "NONE" = rows without a station. */
    private static String stationFilter(String stationCode) {
        if (stationCode == null || stationCode.isBlank()) {
            return null;
        }
        if ("NONE".equalsIgnoreCase(stationCode.trim())) {
            return "";
        }
        return stationCode.trim();
    }

    private Pageable pageable(String sort, String direction, int page, int size) {
        String field = (sort == null || sort.isBlank()) ? "productName" : sort.trim();
        String property = SORTABLE.get(field);
        if (property == null) {
            throw new ValidationException("INVALID_SORT", "Sort field is not supported: " + sort);
        }
        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(dir, property));
    }
}
