package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.catalog.api.InventoryEffectResponse;
import com.smartcoffee.modules.catalog.api.ProductCustomizationCapabilitiesResponse;
import com.smartcoffee.modules.catalog.api.ProductCustomizationGroupView;
import com.smartcoffee.modules.catalog.api.ProductCustomizationGroupsResponse;
import com.smartcoffee.modules.catalog.api.ProductCustomizationOptionView;
import com.smartcoffee.modules.catalog.api.request.ConfigureProductCustomizationGroupRequest;
import com.smartcoffee.modules.catalog.api.request.ConfigureProductCustomizationGroupRequest.OptionOverride;
import com.smartcoffee.modules.catalog.domain.CustomizationGroup;
import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductCustomizationGroup;
import com.smartcoffee.modules.catalog.domain.ProductCustomizationOption;
import com.smartcoffee.modules.catalog.domain.SelectionType;
import com.smartcoffee.modules.catalog.persistence.CustomizationGroupRepository;
import com.smartcoffee.modules.catalog.persistence.CustomizationOptionRepository;
import com.smartcoffee.modules.catalog.persistence.ProductCustomizationGroupRepository;
import com.smartcoffee.modules.catalog.persistence.ProductCustomizationOptionRepository;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Management surface for the product &lt;-&gt; customization-group attachment
 * (Slice 4A Round 2, full stored contract).
 *
 * <p><b>What changed from Round 1.</b> V049 gave the attachment row its own
 * override columns and added the {@code product_customization_options} allow-list
 * table, so the per-product minimum/maximum/default/allowed-subset/price-override
 * fields are now real writes. {@link ProductCustomizationCapabilitiesResponse}
 * therefore reports {@link ProductCustomizationCapabilitiesResponse#roundTwo()}
 * (all overrides supported) and {@code relationshipVersion} is the attachment
 * row's independent {@code version} ({@code RELATIONSHIP}, not {@code PRODUCT}).
 *
 * <p><b>One resolver.</b> Reads and the effective values a write must validate
 * against are taken from {@link EffectiveCustomizationResolver} — the same
 * implementation POS/Customer/Cart/Availability/Consumption use — so the editor
 * can never report a configuration the read paths would ignore (§4).
 *
 * <p><b>Controlled diff.</b> The option subset is updated by diffing the stored
 * allow-list rows against the requested set: dropped options are flagged
 * {@code allowed = false} (history and any price override preserved), added ones
 * inserted, kept ones updated in place. The attachment row itself is only ever
 * created once and then reconfigured — never delete-and-reinsert — so its
 * composite key stays a stable reference.
 *
 * <p><b>Locking.</b> The primary token is the attachment {@code version}
 * (stale = 409 {@code PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT}); the owning
 * {@code Product.version} is still advanced on any membership change and is
 * accepted as a secondary collection-level guard when the client supplies
 * {@code expectedProductVersion}.
 *
 * <p><b>Permissions.</b> {@code product:manage} guards read and write, matching
 * every other management catalog surface in this module.
 */
@Service
public class ProductCustomizationGroupManagementService {

    private static final String VERSION_CONFLICT = "PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT";
    private static final String INVALID_LIMITS = "INVALID_SELECTION_LIMITS";
    private static final String DEFAULT_NOT_ALLOWED = "DEFAULT_OPTION_NOT_ALLOWED";
    private static final String OPTION_NOT_FOUND = "OPTION_NOT_FOUND";
    private static final String OPTION_NOT_ALLOWED = "OPTION_NOT_ALLOWED";
    private static final String DUPLICATE_OPTION = "DUPLICATE_OPTION_SELECTION";
    private static final String INVALID_PRICE = "INVALID_OPTION_PRICE_OVERRIDE";

    private final ProductRepository products;
    private final ProductCustomizationGroupRepository attachments;
    private final ProductCustomizationOptionRepository optionAttachments;
    private final CustomizationGroupRepository groups;
    private final CustomizationOptionRepository options;
    private final PermissionEvaluator permissions;
    private final ProductVariantMapper variantMapper;
    private final AuditWriter audit;
    private final EffectiveCustomizationResolver resolver;

    public ProductCustomizationGroupManagementService(ProductRepository products,
            ProductCustomizationGroupRepository attachments, ProductCustomizationOptionRepository optionAttachments,
            CustomizationGroupRepository groups, CustomizationOptionRepository options,
            PermissionEvaluator permissions, ProductVariantMapper variantMapper, AuditWriter audit,
            EffectiveCustomizationResolver resolver) {
        this.products = products;
        this.attachments = attachments;
        this.optionAttachments = optionAttachments;
        this.groups = groups;
        this.options = options;
        this.permissions = permissions;
        this.variantMapper = variantMapper;
        this.audit = audit;
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------- read

    /**
     * Authoritative management read: every attached group (inactive masters
     * included, flagged) with its effective per-product limits, default, allowed
     * options and price overrides, plus the Round 2 capability declaration.
     */
    @Transactional(readOnly = true)
    public ProductCustomizationGroupsResponse list(CurrentActor actor, UUID productId) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        return project(requireProduct(productId));
    }

    // ------------------------------------------------------------------ write

    /**
     * Attaches the group when absent, reconfigures it when present, applies the
     * full override set and the option-subset diff, and returns the authoritative
     * post-write projection (with the advanced relationship and product versions).
     */
    @Transactional
    public ProductCustomizationGroupsResponse configure(CurrentActor actor, UUID productId, UUID groupId,
            ConfigureProductCustomizationGroupRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        Product product = requireProduct(productId);
        CustomizationGroup group = requireGroup(groupId);
        requireCurrentProductVersion(product, req.expectedProductVersion());

        if (!group.isActive()) {
            throw new ValidationException("CUSTOMIZATION_GROUP_INACTIVE",
                    "Customization group '" + group.code() + "' is inactive and cannot be attached.");
        }

        Map<UUID, EffectiveCustomization.Group> effectiveBefore = effectiveById(productId);
        ProductCustomizationGroup existing = attachments.findByIdProductIdAndIdGroupId(productId, groupId);
        requireCurrentRelationshipVersion(existing, req.relationshipVersion());

        // Resolve the requested limits, treating null as "inherit the master group".
        int effMin = req.minimumSelections() != null ? req.minimumSelections() : group.minimumSelections();
        Integer effMax = req.maximumSelections() != null ? req.maximumSelections() : group.maximumSelections();
        validateSelectionLimits(group, req.requiredOrDefault(), effMin, effMax);

        // Determine the target allowed-option set (ids that stay allowed).
        Map<UUID, CustomizationOption> masterActive = new LinkedHashMap<>();
        for (CustomizationOption o : options.findByGroupIdAndActiveTrueOrderByDisplayOrderAscNameAsc(groupId)) {
            masterActive.put(o.id(), o);
        }
        Set<UUID> targetAllowed = resolveTargetAllowed(req, existing, masterActive, groupId);

        validateDefault(req.defaultOptionId(), targetAllowed, masterActive);

        ProductCustomizationGroupView before = existing == null ? null
                : viewForProduct(product, existing.groupId(), effectiveBefore);

        boolean attaching = existing == null;
        ProductCustomizationGroup attachment;
        if (existing == null) {
            attachment = ProductCustomizationGroup.create(productId, groupId, req.requiredOrDefault(),
                    req.displayOrderOrDefault());
            attachment.configureOverrides(req.requiredOrDefault(), req.displayOrderOrDefault(),
                    req.minimumSelections(), req.maximumSelections(), req.defaultOptionId());
        } else {
            existing.configureOverrides(req.requiredOrDefault(), req.displayOrderOrDefault(),
                    req.minimumSelections(), req.maximumSelections(), req.defaultOptionId());
            existing.markUpdatedBy(actor.userId());
            attachment = existing;
        }
        try {
            attachments.saveAndFlush(attachment);
            applyOptionDiff(productId, groupId, req, masterActive, targetAllowed, actor, attaching);
            bumpProductLock(product);
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException("DUPLICATE_PRODUCT_CUSTOMIZATION_GROUP",
                    "This group was attached concurrently. Refresh before editing again.");
        } catch (IllegalArgumentException shape) {
            throw new ValidationException(INVALID_PRICE, shape.getMessage());
        }

        audit(attaching ? "CATALOG_PRODUCT_GROUP_ATTACH" : "CATALOG_PRODUCT_GROUP_UPDATE", actor, product, before,
                viewForProduct(product, groupId, effectiveAfter(productId)), groupId);
        return project(product);
    }

    /**
     * Detaches the relationship row only. The composite FK cascade removes its
     * option allow-list rows; the master group, its options and every historical
     * cart/order customization snapshot are untouched. A second detach is 404.
     */
    @Transactional
    public ProductCustomizationGroupsResponse detach(CurrentActor actor, UUID productId, UUID groupId,
            Long expectedProductVersion) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        Product product = requireProduct(productId);
        requireGroup(groupId);
        requireCurrentProductVersion(product, expectedProductVersion);

        ProductCustomizationGroup existing = attachments.findByIdProductIdAndIdGroupId(productId, groupId);
        if (existing == null) {
            throw new ResourceNotFoundException("PRODUCT_CUSTOMIZATION_GROUP_NOT_FOUND",
                    "This product has no attachment for the given customization group.");
        }
        ProductCustomizationGroupView before = viewForProduct(product, groupId, effectiveById(productId));

        attachments.delete(existing);
        bumpProductLock(product);

        audit("CATALOG_PRODUCT_GROUP_DETACH", actor, product, before, null, groupId);
        return project(product);
    }

    // ------------------------------------------------------------- projection

    private ProductCustomizationGroupsResponse project(Product product) {
        Map<UUID, EffectiveCustomization.Group> effective = effectiveById(product.id());
        List<ProductCustomizationGroupView> views = new ArrayList<>();
        for (ProductCustomizationGroup attachment : attachments.findAllForProduct(product.id())) {
            views.add(viewForProduct(product, attachment.groupId(), effective));
        }
        return new ProductCustomizationGroupsResponse(product.id(), product.version(),
                ProductCustomizationCapabilitiesResponse.roundTwo(), views);
    }

    /**
     * Builds one management view from the resolver's effective group (limits,
     * default, relationship version, effective prices) enriched with each allowed
     * option's master inventory-effect payload.
     */
    private ProductCustomizationGroupView viewForProduct(Product product, UUID groupId,
            Map<UUID, EffectiveCustomization.Group> effective) {
        EffectiveCustomization.Group g = effective.get(groupId);
        if (g == null) {
            // Attachment present in the table but the resolver omitted it (only possible
            // for an inactive master when the read asked for active-only); re-resolve wide.
            g = resolver.resolve(product.id(), true).stream().filter(x -> x.groupId().equals(groupId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("CUSTOMIZATION_GROUP_NOT_FOUND",
                            "Customization group not found"));
        }
        Map<UUID, CustomizationOption> masterById = new LinkedHashMap<>();
        for (CustomizationOption o : options.findByGroupIdAndActiveTrueOrderByDisplayOrderAscNameAsc(groupId)) {
            masterById.put(o.id(), o);
        }
        List<ProductCustomizationOptionView> optionViews = new ArrayList<>();
        for (EffectiveCustomization.Option o : g.options()) {
            CustomizationOption m = masterById.get(o.optionId());
            InventoryEffectResponse effect = m == null ? null : variantMapper.toEffectResponse(m);
            optionViews.add(new ProductCustomizationOptionView(o.optionId(), o.code(), o.name(), o.masterActive(),
                    o.isDefault(), o.effectivePrice(), o.displayOrder(), null, o.optionVersion(), effect));
        }
        return new ProductCustomizationGroupView(null, product.id(), g.groupId(), g.code(), g.name(),
                g.selectionType(), g.groupActive(), g.required(), g.displayOrder(), g.minimumSelections(),
                g.maximumSelections(), g.defaultOptionId(), g.relationshipVersion(), optionViews);
    }

    private Map<UUID, EffectiveCustomization.Group> effectiveById(UUID productId) {
        Map<UUID, EffectiveCustomization.Group> map = new LinkedHashMap<>();
        for (EffectiveCustomization.Group g : resolver.resolve(productId, true)) {
            map.put(g.groupId(), g);
        }
        return map;
    }

    private Map<UUID, EffectiveCustomization.Group> effectiveAfter(UUID productId) {
        return effectiveById(productId);
    }

    // -------------------------------------------------------------- invariant

    private Product requireProduct(UUID productId) {
        return products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
    }

    private CustomizationGroup requireGroup(UUID groupId) {
        return groups.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("CUSTOMIZATION_GROUP_NOT_FOUND",
                        "Customization group not found"));
    }

    /** The owning product is still the collection-level token when the client supplies it. */
    private void requireCurrentProductVersion(Product product, Long expectedProductVersion) {
        if (expectedProductVersion != null && expectedProductVersion != product.version()) {
            throw conflict();
        }
    }

    /** The attachment row's own version is the Round 2 relationship lock token. */
    private void requireCurrentRelationshipVersion(ProductCustomizationGroup existing, Long relationshipVersion) {
        if (relationshipVersion == null) {
            return;
        }
        long current = existing == null ? 0L : existing.version();
        if (relationshipVersion != current) {
            throw conflict();
        }
    }

    private ConflictException conflict() {
        return new ConflictException(VERSION_CONFLICT,
                "This product's customization configuration was updated by another user. Refresh before editing again.");
    }

    private void validateSelectionLimits(CustomizationGroup group, boolean required, int effMin, Integer effMax) {
        if (effMin < 0) {
            throw new ValidationException(INVALID_LIMITS, "minimumSelections must be non-negative.");
        }
        if (effMax != null && effMax < 0) {
            throw new ValidationException(INVALID_LIMITS, "maximumSelections must be non-negative.");
        }
        if (effMax != null && effMax < effMin) {
            throw new ValidationException(INVALID_LIMITS,
                    "maximumSelections (" + effMax + ") must be >= minimumSelections (" + effMin + ").");
        }
        if (group.selectionType() == SelectionType.SINGLE && (effMax == null || effMax != 1)) {
            throw new ValidationException(INVALID_LIMITS, "A SINGLE group must cap maximumSelections at 1.");
        }
        if (required && effMin < 1) {
            throw new ValidationException(INVALID_LIMITS,
                    "A required group must allow at least one selection (minimumSelections >= 1).");
        }
    }

    /**
     * Resolves the set of option ids that should remain allowed after this write.
     * An explicit {@code options} list wins over the {@code allowedOptionIds}
     * shortcut; sending neither keeps the current subset (seeding every active
     * master option when first attaching).
     */
    private Set<UUID> resolveTargetAllowed(ConfigureProductCustomizationGroupRequest req,
            ProductCustomizationGroup existing, Map<UUID, CustomizationOption> masterActive, UUID groupId) {
        if (req.options() != null) {
            Set<UUID> seen = new LinkedHashSet<>();
            Set<UUID> allowed = new LinkedHashSet<>();
            for (OptionOverride ov : req.options()) {
                if (ov.optionId() == null) {
                    throw new ValidationException(OPTION_NOT_FOUND, "option override must name an option id.");
                }
                if (!seen.add(ov.optionId())) {
                    throw new ValidationException(DUPLICATE_OPTION,
                            "The same option was supplied twice: " + ov.optionId());
                }
                validateOptionBelongsToGroup(ov.optionId(), groupId, masterActive);
                if (!Boolean.FALSE.equals(ov.allowed())) {
                    allowed.add(ov.optionId());
                }
            }
            return allowed;
        }
        if (req.allowedOptionIds() != null) {
            Set<UUID> allowed = new LinkedHashSet<>();
            if (!allowed.addAll(req.allowedOptionIds())) {
                // fall through; duplicates collapse
            }
            if (new LinkedHashSet<>(req.allowedOptionIds()).size() != req.allowedOptionIds().size()) {
                throw new ValidationException(DUPLICATE_OPTION, "allowedOptionIds contains duplicates.");
            }
            for (UUID id : allowed) {
                validateOptionBelongsToGroup(id, groupId, masterActive);
            }
            return allowed;
        }
        // No membership field: keep the existing allowed rows, or seed all active on attach.
        if (existing == null) {
            return new LinkedHashSet<>(masterActive.keySet());
        }
        Set<UUID> keep = new LinkedHashSet<>();
        for (ProductCustomizationOption row : optionAttachments.findByProductIdAndGroupIdAndActiveTrueOrderByDisplayOrderAsc(
                existing.productId(), groupId)) {
            if (row.allowed() && masterActive.containsKey(row.optionId())) {
                keep.add(row.optionId());
            }
        }
        return keep;
    }

    private void validateOptionBelongsToGroup(UUID optionId, UUID groupId, Map<UUID, CustomizationOption> masterActive) {
        if (masterActive.containsKey(optionId)) {
            return;
        }
        CustomizationOption o = options.findById(optionId)
                .orElseThrow(() -> new ValidationException(OPTION_NOT_FOUND, "Option does not exist: " + optionId));
        if (!o.groupId().equals(groupId)) {
            throw new ValidationException(OPTION_NOT_ALLOWED, "Option " + optionId + " does not belong to this group.");
        }
        throw new ValidationException(OPTION_NOT_ALLOWED,
                "Option " + optionId + " is inactive and cannot be newly allowed or set as default.");
    }

    private void validateDefault(UUID defaultOptionId, Set<UUID> targetAllowed,
            Map<UUID, CustomizationOption> masterActive) {
        if (defaultOptionId == null) {
            return;
        }
        if (!targetAllowed.contains(defaultOptionId) || !masterActive.containsKey(defaultOptionId)) {
            throw new ValidationException(DEFAULT_NOT_ALLOWED,
                    "defaultOptionId must be one of the effective allowed options of this group.");
        }
    }

    /**
     * Applies the option-subset diff in place: kept options update their allowed
     * flag / price override / order, removed options are flagged not-allowed, and
     * new options are inserted. Never delete-and-reinsert, so price overrides and
     * audit history on retained rows survive unrelated edits.
     */
    private void applyOptionDiff(UUID productId, UUID groupId, ConfigureProductCustomizationGroupRequest req,
            Map<UUID, CustomizationOption> masterActive, Set<UUID> targetAllowed, CurrentActor actor,
            boolean attaching) {
        Map<UUID, OptionOverride> overrides = new LinkedHashMap<>();
        if (req.options() != null) {
            for (OptionOverride ov : req.options()) {
                overrides.put(ov.optionId(), ov);
            }
        }
        Map<UUID, ProductCustomizationOption> existingRows = new LinkedHashMap<>();
        if (!attaching) {
            for (ProductCustomizationOption row : optionAttachments
                    .findByProductIdAndGroupIdAndActiveTrueOrderByDisplayOrderAsc(productId, groupId)) {
                existingRows.put(row.optionId(), row);
            }
        }

        // Every active master option is a candidate row so removed ones get flagged, not orphaned.
        for (UUID optionId : masterActive.keySet()) {
            boolean shouldAllow = targetAllowed.contains(optionId);
            OptionOverride ov = overrides.get(optionId);
            ProductCustomizationOption row = existingRows.get(optionId);
            if (row == null) {
                if (shouldAllow || ov != null) {
                    int order = orderFor(ov, masterActive.get(optionId), existingRows, optionId);
                    ProductCustomizationOption created = ProductCustomizationOption.allow(productId, groupId,
                            optionId, order);
                    created.configure(shouldAllow, priceFor(ov), order);
                    created.markUpdatedBy(actor.userId());
                    optionAttachments.save(created);
                }
            } else {
                BigDecimal price = priceFor(ov);
                int order = ov != null && ov.displayOrder() != null ? ov.displayOrder() : row.displayOrder();
                if (attaching && price == null && ov == null) {
                    order = masterActive.get(optionId).displayOrder();
                }
                row.configure(shouldAllow, price, order);
                row.markUpdatedBy(actor.userId());
                optionAttachments.save(row);
            }
        }
    }

    private BigDecimal priceFor(OptionOverride ov) {
        if (ov == null) {
            return null;
        }
        BigDecimal price = ov.priceOverride();
        if (price != null && price.signum() < 0) {
            throw new ValidationException(INVALID_PRICE, "priceOverride must be non-negative.");
        }
        return price;
    }

    private int orderFor(OptionOverride ov, CustomizationOption master, Map<UUID, ProductCustomizationOption> existing,
            UUID optionId) {
        if (ov != null && ov.displayOrder() != null) {
            return ov.displayOrder();
        }
        ProductCustomizationOption prior = existing.get(optionId);
        return prior != null ? prior.displayOrder() : master.displayOrder();
    }

    /**
     * Advances the collection-level product lock. The pre-checks turn the common
     * stale case into a clean 409; this catch maps the read/flush race so
     * Hibernate's own failure still surfaces as the project's stable code.
     */
    private void bumpProductLock(Product product) {
        try {
            product.childCollectionChanged();
            products.saveAndFlush(product);
        } catch (ObjectOptimisticLockingFailureException concurrent) {
            throw conflict();
        }
    }

    /**
     * Audit stores only the safe projected view (never a managed entity) so the
     * jsonb snapshot cannot leak lazy state.
     */
    private void audit(String action, CurrentActor actor, Product product, ProductCustomizationGroupView before,
            ProductCustomizationGroupView after, UUID groupId) {
        audit.success(actor.userId(), null, action, "CATALOG", "PRODUCT", product.id(), before, after,
                (after == null ? "Detached" : "Configured") + " customization group " + groupId
                        + " on product " + product.code());
    }

    /** Exposed for the capability declaration in tests and for clients reading the contract. */
    public ProductCustomizationCapabilitiesResponse capabilities() {
        return ProductCustomizationCapabilitiesResponse.roundTwo();
    }
}
