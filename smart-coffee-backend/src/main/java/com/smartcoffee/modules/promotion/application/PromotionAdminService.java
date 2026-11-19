package com.smartcoffee.modules.promotion.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.promotion.api.CreatePromotionRequest;
import com.smartcoffee.modules.promotion.api.PromotionAdminResponse;
import com.smartcoffee.modules.promotion.api.PromotionStatusRequest;
import com.smartcoffee.modules.promotion.api.UpdatePromotionRequest;
import com.smartcoffee.modules.promotion.domain.DiscountType;
import com.smartcoffee.modules.promotion.domain.Promotion;
import com.smartcoffee.modules.promotion.domain.PromotionStatus;
import com.smartcoffee.modules.promotion.persistence.PromotionAssignmentRepository;
import com.smartcoffee.modules.promotion.persistence.PromotionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotions & campaigns administration (Master Prompt §9). Everything exposed
 * here is backed by promotion.promotions columns and the three assignment join
 * tables — no field is invented. Historical redemptions are sacred: usage_count
 * moves only through the existing claim/release engine, status only through the
 * validated transition table, and the code is immutable identity. The
 * customer-facing POST /api/v1/promotions/validate path is untouched.
 */
@SuppressWarnings("null")
@Service
public class PromotionAdminService {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "KHR");
    private static final Pattern PROMOTION_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{2,49}$");
    private static final Map<PromotionStatus, Set<PromotionStatus>> ALLOWED_TRANSITIONS = Map.of(
            PromotionStatus.DRAFT, Set.of(PromotionStatus.ACTIVE, PromotionStatus.CANCELLED),
            PromotionStatus.ACTIVE, Set.of(PromotionStatus.PAUSED, PromotionStatus.CANCELLED),
            PromotionStatus.PAUSED, Set.of(PromotionStatus.ACTIVE, PromotionStatus.CANCELLED),
            // Terminal states: expiry is time-driven and cancellation is final;
            // resurrecting either through this API would rewrite live campaign history.
            PromotionStatus.EXPIRED, Set.of(),
            PromotionStatus.CANCELLED, Set.of());

    private final PromotionRepository promotions;
    private final PromotionAssignmentRepository links;
    private final AuditWriter audit;

    public PromotionAdminService(PromotionRepository promotions, PromotionAssignmentRepository links,
            AuditWriter audit) {
        this.promotions = promotions;
        this.links = links;
        this.audit = audit;
    }

    // ------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<PromotionAdminResponse> list(String status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Order.desc("startsAt"),
                Sort.Order.desc("id")));
        Page<Promotion> rows;
        if (status == null || status.isBlank()) {
            rows = promotions.findAll(pageable);
        } else {
            rows = promotions.findByStatus(parseStatus(status), pageable);
        }
        // Assignment payloads are intentionally omitted on the list page; the
        // detail endpoint returns them in full.
        return rows.map(p -> toResponse(p, null, null, null));
    }

    @Transactional(readOnly = true)
    public PromotionAdminResponse detail(UUID promotionId) {
        Promotion p = load(promotionId);
        return toResponse(p, ids(links.productsFor(p.id())), ids(links.categoriesFor(p.id())),
                ids(links.storesFor(p.id())));
    }

    // ------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------

    @Transactional
    public PromotionAdminResponse create(CurrentActor actor, CreatePromotionRequest req) {
        String code = req.promotionCode() == null ? null : req.promotionCode().trim().toUpperCase(Locale.ROOT);
        if (code == null || !PROMOTION_CODE.matcher(code).matches()) {
            throw new ValidationException("INVALID_PROMOTION_CODE",
                    "Promotion code must be 3-50 characters: letters, digits, underscore or hyphen");
        }
        validateName(req.promotionName());
        if (promotions.existsByCode(code)) {
            throw new ConflictException("PROMOTION_CODE_TAKEN", "Promotion code is already in use");
        }

        DiscountType type = parseDiscountType(req.discountType());
        validatePeriod(req.startsAt(), req.endsAt());
        BigDecimal value = validateDiscountValue(type, req.discountValue());
        BigDecimal minimum = req.minimumOrderAmount() == null ? BigDecimal.ZERO : req.minimumOrderAmount();
        validateNonNegative(minimum, "INVALID_MINIMUM_ORDER_AMOUNT", "Minimum order amount must be zero or positive");
        validateNonNegative(req.maximumDiscountAmount(), "INVALID_MAXIMUM_DISCOUNT",
                "Maximum discount amount must be zero or positive");
        String currency = validateCurrency(type, req.maximumDiscountAmount(), req.currencyCode());
        validateLimits(req.usageLimit(), req.perCustomerLimit());

        PromotionStatus initial = req.status() == null || req.status().isBlank() ? PromotionStatus.DRAFT
                : parseStatus(req.status());
        if (initial != PromotionStatus.DRAFT && initial != PromotionStatus.ACTIVE) {
            throw new ValidationException("INVALID_INITIAL_STATUS", "A new campaign must start as DRAFT or ACTIVE");
        }
        if (initial == PromotionStatus.ACTIVE && req.endsAt().isBefore(Instant.now())) {
            throw new ValidationException("INVALID_PERIOD", "An expired campaign cannot start as ACTIVE");
        }

        validateTargets(req.productIds(), req.categoryIds(), req.storeIds());

        // saveAndFlush: the native assignment inserts below reference the parent
        // row via FK; persist-then-native-insert ordering is not guaranteed.
        Promotion saved = promotions.saveAndFlush(Promotion.create(code, req.promotionName().trim(), type, value,
                minimum, req.maximumDiscountAmount(), currency, req.startsAt(), req.endsAt(), initial,
                req.usageLimit(), req.perCustomerLimit(), Boolean.TRUE.equals(req.stackable()),
                Boolean.TRUE.equals(req.automatic()), req.priority() == null ? 0 : req.priority()));
        replaceProducts(saved.id(), req.productIds());
        replaceCategories(saved.id(), req.categoryIds());
        replaceStores(saved.id(), req.storeIds());

        PromotionAdminResponse after = detailInternal(saved.id());
        audit.success(actor.userId(), null, "PROMOTION_CREATED", "PROMOTION", "PROMOTION", saved.id(), null, after,
                "code=" + code);
        return after;
    }

    @Transactional
    public PromotionAdminResponse update(CurrentActor actor, UUID promotionId, UpdatePromotionRequest req) {
        Promotion p = load(promotionId);
        requireVersion(req.version(), p.version());

        validateName(req.promotionName());
        DiscountType type = parseDiscountType(req.discountType());
        validatePeriod(req.startsAt(), req.endsAt());
        BigDecimal value = validateDiscountValue(type, req.discountValue());
        BigDecimal minimum = req.minimumOrderAmount() == null ? BigDecimal.ZERO : req.minimumOrderAmount();
        validateNonNegative(minimum, "INVALID_MINIMUM_ORDER_AMOUNT", "Minimum order amount must be zero or positive");
        validateNonNegative(req.maximumDiscountAmount(), "INVALID_MAXIMUM_DISCOUNT",
                "Maximum discount amount must be zero or positive");
        String currency = validateCurrency(type, req.maximumDiscountAmount(), req.currencyCode());
        validateLimits(req.usageLimit(), req.perCustomerLimit());
        validateTargets(req.productIds(), req.categoryIds(), req.storeIds());

        PromotionAdminResponse before = detailInternal(p.id());
        p.rename(req.promotionName().trim());
        p.updateDefinition(type, value, minimum, req.maximumDiscountAmount(), currency, req.startsAt(), req.endsAt(),
                req.usageLimit(), req.perCustomerLimit(), Boolean.TRUE.equals(req.stackable()),
                Boolean.TRUE.equals(req.automatic()), req.priority() == null ? 0 : req.priority());
        promotions.saveAndFlush(p);
        if (req.productIds() != null) {
            replaceProducts(p.id(), req.productIds());
        }
        if (req.categoryIds() != null) {
            replaceCategories(p.id(), req.categoryIds());
        }
        if (req.storeIds() != null) {
            replaceStores(p.id(), req.storeIds());
        }

        PromotionAdminResponse after = detailInternal(p.id());
        audit.success(actor.userId(), null, "PROMOTION_UPDATED", "PROMOTION", "PROMOTION", p.id(), before, after,
                "code=" + p.code() + " usageCountPreserved=" + p.usageCount());
        return after;
    }

    @Transactional
    public PromotionAdminResponse changeStatus(CurrentActor actor, UUID promotionId, PromotionStatusRequest req) {
        Promotion p = load(promotionId);
        requireVersion(req.version(), p.version());
        PromotionStatus target = parseStatus(req.status());

        if (!ALLOWED_TRANSITIONS.get(p.status()).contains(target)) {
            throw new ValidationException("INVALID_STATUS_TRANSITION",
                    "Transition " + p.status() + " -> " + target + " is not allowed");
        }
        if (target == PromotionStatus.ACTIVE) {
            if (p.endsAt().isBefore(Instant.now())) {
                throw new ValidationException("INVALID_PERIOD", "An expired campaign cannot be activated");
            }
            if (p.usageLimit() != null && p.usageCount() >= p.usageLimit()) {
                throw new ValidationException("PROMOTION_EXHAUSTED", "Usage limit already reached");
            }
        }

        PromotionAdminResponse before = detailInternal(p.id());
        PromotionStatus previous = p.status();
        p.changeStatus(target);
        promotions.saveAndFlush(p);

        PromotionAdminResponse after = detailInternal(p.id());
        String reason = previous + " -> " + target + (req.reason() == null || req.reason().isBlank() ? ""
                : " | " + req.reason().trim());
        audit.success(actor.userId(), null, "PROMOTION_STATUS_CHANGED", "PROMOTION", "PROMOTION", p.id(), before,
                after, reason);
        return after;
    }

    // ------------------------------------------------------------
    // Guards + authoritative validation
    // ------------------------------------------------------------

    private Promotion load(UUID promotionId) {
        return promotions.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("PROMO_NOT_FOUND", "Promotion not found"));
    }

    private PromotionAdminResponse detailInternal(UUID promotionId) {
        Promotion p = load(promotionId);
        return toResponse(p, ids(links.productsFor(p.id())), ids(links.categoriesFor(p.id())),
                ids(links.storesFor(p.id())));
    }

    private void requireVersion(long expected, long current) {
        if (expected != current) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This campaign was updated by another user. Refresh before editing again.");
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 150) {
            throw new ValidationException("INVALID_PROMOTION_NAME", "Promotion name is required (max 150 chars)");
        }
    }

    private DiscountType parseDiscountType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("INVALID_DISCOUNT_TYPE", "Discount type is required");
        }
        try {
            return DiscountType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("INVALID_DISCOUNT_TYPE", "Discount type must be PERCENTAGE or FIXED_AMOUNT");
        }
    }

    private PromotionStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("INVALID_STATUS", "Status is required");
        }
        try {
            return PromotionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("INVALID_STATUS", "Unknown promotion status: " + raw.trim());
        }
    }

    private void validatePeriod(Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new ValidationException("INVALID_PERIOD", "Start and end are required and end must be after start");
        }
    }

    private BigDecimal validateDiscountValue(DiscountType type, BigDecimal value) {
        // ck_promo_values persists discount_value > 0 for both types; percentage
        // additionally cannot exceed 100.
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("INVALID_DISCOUNT_VALUE", "Discount value must be positive");
        }
        if (type == DiscountType.PERCENTAGE && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ValidationException("INVALID_DISCOUNT_VALUE", "Percentage discount cannot exceed 100");
        }
        return value;
    }

    private void validateNonNegative(BigDecimal value, String code, String message) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException(code, message);
        }
    }

    private String validateCurrency(DiscountType type, BigDecimal maximum, String currencyCode) {
        String currency = currencyCode == null || currencyCode.isBlank() ? null
                : currencyCode.trim().toUpperCase(Locale.ROOT);
        boolean monetaryRequired = type == DiscountType.FIXED_AMOUNT || maximum != null;
        if (monetaryRequired && currency == null) {
            throw new ValidationException("MISSING_CURRENCY",
                    "Currency is required for monetary discounts or monetary caps");
        }
        if (currency != null && !SUPPORTED_CURRENCIES.contains(currency)) {
            throw new ValidationException("UNSUPPORTED_CURRENCY", "Currency is not supported");
        }
        return currency;
    }

    private void validateLimits(Integer usageLimit, Integer perCustomerLimit) {
        if (usageLimit != null && usageLimit <= 0) {
            throw new ValidationException("INVALID_USAGE_LIMIT", "Usage limit must be positive when set");
        }
        if (perCustomerLimit != null && perCustomerLimit <= 0) {
            throw new ValidationException("INVALID_PER_CUSTOMER_LIMIT", "Per-customer limit must be positive when set");
        }
        if (usageLimit != null && perCustomerLimit != null && perCustomerLimit > usageLimit) {
            throw new ValidationException("INVALID_PER_CUSTOMER_LIMIT",
                    "Per-customer limit cannot exceed the total usage limit");
        }
    }

    private void validateTargets(List<UUID> productIds, List<UUID> categoryIds, List<UUID> storeIds) {
        requireExisting("product", productIds, links::existingProductIds);
        requireExisting("category", categoryIds, links::existingCategoryIds);
        requireExisting("store", storeIds, links::existingStoreIds);
    }

    private void requireExisting(String kind, List<UUID> requested,
            java.util.function.Function<UUID[], List<UUID>> existingQuery) {
        if (requested == null || requested.isEmpty()) {
            return;
        }
        Set<UUID> wanted = new LinkedHashSet<>(requested);
        wanted.removeIf(java.util.Objects::isNull);
        if (wanted.isEmpty()) {
            return;
        }
        Set<UUID> found = new LinkedHashSet<>(existingQuery.apply(wanted.toArray(new UUID[0])));
        if (!found.containsAll(wanted)) {
            throw new ValidationException("TARGET_NOT_FOUND", "One or more target " + kind + "s do not exist");
        }
    }

    // ------------------------------------------------------------
    // Assignment replacement (transactional, deduped)
    // ------------------------------------------------------------

    private void replaceProducts(UUID promotionId, List<UUID> productIds) {
        links.deleteProducts(promotionId);
        for (UUID id : distinct(productIds)) {
            links.insertProduct(promotionId, id);
        }
    }

    private void replaceCategories(UUID promotionId, List<UUID> categoryIds) {
        links.deleteCategories(promotionId);
        for (UUID id : distinct(categoryIds)) {
            links.insertCategory(promotionId, id);
        }
    }

    private void replaceStores(UUID promotionId, List<UUID> storeIds) {
        links.deleteStores(promotionId);
        for (UUID id : distinct(storeIds)) {
            links.insertStore(promotionId, id);
        }
    }

    private List<UUID> distinct(List<UUID> ids) {
        if (ids == null) {
            return List.of();
        }
        return new java.util.ArrayList<>(new LinkedHashSet<>(ids));
    }

    private List<UUID> ids(List<PromotionAssignmentRepository.PromotionLinkRow> rows) {
        return rows.stream().map(PromotionAssignmentRepository.PromotionLinkRow::getLinkId).toList();
    }

    // ------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------

    private PromotionAdminResponse toResponse(Promotion p, List<UUID> productIds, List<UUID> categoryIds,
            List<UUID> storeIds) {
        return new PromotionAdminResponse(p.id(), p.code(), p.name(), p.discountType().name(), p.discountValue(),
                p.minimumOrderAmount(), p.maximumDiscountAmount(), p.currency(), p.startsAt(), p.endsAt(),
                p.status().name(), p.usageLimit(), p.perCustomerLimit(), p.usageCount(), p.stackable(),
                p.automatic(), p.priority(), p.version(), productIds, categoryIds, storeIds);
    }
}
