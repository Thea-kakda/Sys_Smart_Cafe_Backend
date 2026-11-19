package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.catalog.api.CustomizationGroupMasterResponse;
import com.smartcoffee.modules.catalog.api.CustomizationOptionMasterResponse;
import com.smartcoffee.modules.catalog.api.request.CreateCustomizationGroupRequest;
import com.smartcoffee.modules.catalog.api.request.CreateCustomizationOptionRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCustomizationGroupRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCustomizationOptionRequest;
import com.smartcoffee.modules.catalog.domain.CustomizationGroup;
import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.ProductCustomizationGroup;
import com.smartcoffee.modules.catalog.persistence.CustomizationGroupRepository;
import com.smartcoffee.modules.catalog.persistence.CustomizationOptionRepository;
import com.smartcoffee.modules.catalog.persistence.ProductCustomizationGroupRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Master customization management (Slice 4A Round 2, §7): create/edit groups,
 * create/edit/reorder/activate option values. Before this service the masters
 * were migration-seeded and read-only, which blocked the Dashboard Options tab
 * from operating the domain it already displays.
 *
 * <p><b>Scope discipline.</b> This is the existing domain, not a second model:
 * the same {@link CustomizationGroup}/{@link CustomizationOption} rows the POS,
 * Customer and resolver paths read. Product-specific configuration stays on
 * {@link ProductCustomizationGroupManagementService}; the inventory effect
 * stays on {@link OptionInventoryEffectCommandService} (one write surface per
 * concern). No migration was needed — the master tables already carry every
 * field.
 *
 * <p><b>Immutables.</b> Group code and selection type, and option code and
 * group membership, never change after creation: orders, attachments and the
 * product allow-list key on those identities. Activation is a flag, never a
 * delete, so history survives.
 *
 * <p><b>Locking and cross-aggregate safety.</b> Every update echoes the row's
 * {@code @Version} (409 *_VERSION_CONFLICT). Narrowing a group's limits is
 * refused while any product's EFFECTIVE limits (override else master) would
 * violate the new range — the same invariant §6 guards from the product side,
 * now defended from the master side too.
 */
@Service
public class CustomizationMasterManagementService {

    private static final String GROUP_NOT_FOUND = "CUSTOMIZATION_GROUP_NOT_FOUND";
    private static final String OPTION_NOT_FOUND = "CUSTOMIZATION_OPTION_NOT_FOUND";
    private static final String GROUP_VERSION_CONFLICT = "CUSTOMIZATION_GROUP_VERSION_CONFLICT";
    private static final String OPTION_VERSION_CONFLICT = "CUSTOMIZATION_OPTION_VERSION_CONFLICT";
    private static final String DUPLICATE_GROUP = "DUPLICATE_CUSTOMIZATION_GROUP";
    private static final String DUPLICATE_OPTION = "DUPLICATE_CUSTOMIZATION_OPTION";
    private static final String INVALID_LIMITS = "INVALID_SELECTION_LIMITS";
    private static final String INVALID_OPTION = "INVALID_CUSTOMIZATION_OPTION";
    private static final String MASTER_BREAKS_OVERRIDES = "MASTER_LIMITS_BREAK_PRODUCT_OVERRIDES";
    private static final String INVALID_REORDER = "INVALID_REORDER_SET";

    private final CustomizationGroupRepository groups;
    private final CustomizationOptionRepository options;
    private final ProductCustomizationGroupRepository attachments;
    private final PermissionEvaluator permissions;
    private final ProductVariantMapper variantMapper;
    private final AuditWriter audit;

    public CustomizationMasterManagementService(CustomizationGroupRepository groups,
            CustomizationOptionRepository options, ProductCustomizationGroupRepository attachments,
            PermissionEvaluator permissions, ProductVariantMapper variantMapper, AuditWriter audit) {
        this.groups = groups;
        this.options = options;
        this.attachments = attachments;
        this.permissions = permissions;
        this.variantMapper = variantMapper;
        this.audit = audit;
    }

    // ------------------------------------------------------------------- read

    /** Every master group with every option (inactive included, flagged). */
    @Transactional(readOnly = true)
    public List<CustomizationGroupMasterResponse> list(CurrentActor actor) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        return groups.findAllByOrderByDisplayOrderAscNameAsc().stream().map(this::toResponse).toList();
    }

    // ---------------------------------------------------------------- groups

    @Transactional
    public CustomizationGroupMasterResponse createGroup(CurrentActor actor, CreateCustomizationGroupRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        requireNoDuplicateGroupCode(req.code(), null);
        CustomizationGroup group;
        try {
            group = CustomizationGroup.create(req.code(), req.name(), req.selectionType(),
                    req.minimumSelections(), req.maximumSelections(), req.displayOrder());
        } catch (IllegalArgumentException shape) {
            throw new ValidationException(INVALID_LIMITS, shape.getMessage());
        }
        try {
            groups.saveAndFlush(group);
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException(DUPLICATE_GROUP, "Group code already exists: " + req.code());
        }
        audit("CATALOG_CUSTOMIZATION_GROUP_CREATE", actor, group.id(), null, toResponse(group),
                "Created customization group " + group.code());
        return toResponse(group);
    }

    @Transactional
    public CustomizationGroupMasterResponse updateGroup(CurrentActor actor, UUID groupId,
            UpdateCustomizationGroupRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        CustomizationGroup group = requireGroup(groupId);
        requireVersion(group.version(), req.version(), GROUP_VERSION_CONFLICT);
        CustomizationGroupMasterResponse before = toResponse(group);
        validateMasterLimitsAgainstProducts(group, req.minimumSelections(), req.maximumSelections());
        try {
            group.updateMetadata(req.name(), req.minimumSelections(), req.maximumSelections(), req.displayOrder());
        } catch (IllegalArgumentException shape) {
            throw new ValidationException(INVALID_LIMITS, shape.getMessage());
        }
        groups.saveAndFlush(group);
        audit("CATALOG_CUSTOMIZATION_GROUP_UPDATE", actor, groupId, before, toResponse(group),
                "Updated customization group " + group.code());
        return toResponse(group);
    }

    @Transactional
    public CustomizationGroupMasterResponse setGroupActive(CurrentActor actor, UUID groupId, boolean active) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        CustomizationGroup group = requireGroup(groupId);
        CustomizationGroupMasterResponse before = toResponse(group);
        group.markActive(active);
        groups.saveAndFlush(group);
        audit(active ? "CATALOG_CUSTOMIZATION_GROUP_ACTIVATE" : "CATALOG_CUSTOMIZATION_GROUP_DEACTIVATE",
                actor, groupId, before, toResponse(group),
                (active ? "Activated" : "Deactivated") + " customization group " + group.code());
        return toResponse(group);
    }

    // --------------------------------------------------------------- options

    @Transactional
    public CustomizationOptionMasterResponse createOption(CurrentActor actor, UUID groupId,
            CreateCustomizationOptionRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        CustomizationGroup group = requireGroup(groupId);
        requireNoDuplicateOptionCode(group, req.code(), null);
        CustomizationOption option;
        try {
            option = CustomizationOption.create(groupId, req.code(), req.name(), req.additionalPrice(),
                    req.displayOrder());
        } catch (IllegalArgumentException shape) {
            throw new ValidationException(INVALID_OPTION, shape.getMessage());
        }
        try {
            options.saveAndFlush(option);
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException(DUPLICATE_OPTION,
                    "Option code already exists in this group: " + req.code());
        }
        audit("CATALOG_CUSTOMIZATION_OPTION_CREATE", actor, groupId, null, toResponse(option),
                "Created option " + option.code() + " in group " + group.code());
        return toResponse(option);
    }

    @Transactional
    public CustomizationOptionMasterResponse updateOption(CurrentActor actor, UUID optionId,
            UpdateCustomizationOptionRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        CustomizationOption option = requireOption(optionId);
        requireVersion(option.version(), req.version(), OPTION_VERSION_CONFLICT);
        CustomizationOptionMasterResponse before = toResponse(option);
        try {
            option.updateValue(req.name(), req.additionalPrice(), req.displayOrder());
        } catch (IllegalArgumentException shape) {
            throw new ValidationException(INVALID_OPTION, shape.getMessage());
        }
        options.saveAndFlush(option);
        audit("CATALOG_CUSTOMIZATION_OPTION_UPDATE", actor, option.groupId(), before, toResponse(option),
                "Updated option " + option.code());
        return toResponse(option);
    }

    @Transactional
    public CustomizationOptionMasterResponse setOptionActive(CurrentActor actor, UUID optionId, boolean active) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        CustomizationOption option = requireOption(optionId);
        CustomizationOptionMasterResponse before = toResponse(option);
        option.markActive(active);
        options.saveAndFlush(option);
        audit(active ? "CATALOG_CUSTOMIZATION_OPTION_ACTIVATE" : "CATALOG_CUSTOMIZATION_OPTION_DEACTIVATE",
                actor, option.groupId(), before, toResponse(option),
                (active ? "Activated" : "Deactivated") + " option " + option.code());
        return toResponse(option);
    }

    /**
     * Rewrites displayOrder to the array index for a complete ordering of one
     * group's options (inactive included, so a hidden option cannot drift out of
     * the stored order). A partial or foreign list is refused — last-write-wins
     * on a subset would silently reorder rows the caller never saw.
     */
    @Transactional
    public List<CustomizationOptionMasterResponse> reorderOptions(CurrentActor actor, UUID groupId,
            List<UUID> optionIds) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        CustomizationGroup group = requireGroup(groupId);
        List<CustomizationOption> current = options.findByGroupIdOrderByDisplayOrderAscNameAsc(groupId);
        List<UUID> requested = optionIds.stream().distinct().toList();
        boolean exactSet = requested.size() == optionIds.size()
                && requested.size() == current.size()
                && current.stream().allMatch(o -> requested.contains(o.id()));
        if (!exactSet) {
            throw new ValidationException(INVALID_REORDER,
                    "The reorder list must contain every option of the group exactly once.");
        }
        for (int i = 0; i < current.size(); i++) {
            final int index = i;
            CustomizationOption option = current.stream().filter(o -> o.id().equals(requested.get(index)))
                    .findFirst().orElseThrow();
            option.reorderTo(index);
        }
        options.saveAll(current);
        options.flush();
        audit("CATALOG_CUSTOMIZATION_OPTION_REORDER", actor, groupId, null, null,
                "Reordered options of customization group " + group.code());
        return toResponse(group).options();
    }

    // ------------------------------------------------------------- invariants

    /**
     * A master limit change is refused while some product's EFFECTIVE limits
     * (per-product override else the new master value) would end up invalid:
     * effective max below effective min, or a required attachment inheriting
     * min 0. This keeps §6's invariant true for writes the product side cannot
     * see at the time it validates.
     */
    private void validateMasterLimitsAgainstProducts(CustomizationGroup group, int newMin, Integer newMax) {
        for (ProductCustomizationGroup attachment : attachments.findByIdGroupId(group.id())) {
            int effMin = attachment.minimumSelections() != null ? attachment.minimumSelections() : newMin;
            Integer effMax = attachment.maximumSelections() != null ? attachment.maximumSelections() : newMax;
            if (effMax != null && effMax < effMin) {
                throw new ValidationException(MASTER_BREAKS_OVERRIDES,
                        "Product " + attachment.productId() + " would end up with effective max ("
                                + effMax + ") below effective min (" + effMin + "). Fix its overrides first.");
            }
            if (attachment.required() && effMin < 1) {
                throw new ValidationException(MASTER_BREAKS_OVERRIDES,
                        "Required product " + attachment.productId() + " would inherit a minimum below 1. "
                                + "Set its own minimum first.");
            }
        }
    }

    private void requireNoDuplicateGroupCode(String code, UUID selfId) {
        groups.findByCodeIgnoreCase(code.trim()).ifPresent(existing -> {
            if (selfId == null || !existing.id().equals(selfId)) {
                throw new ConflictException(DUPLICATE_GROUP, "Group code already exists: " + code);
            }
        });
    }

    private void requireNoDuplicateOptionCode(CustomizationGroup group, String code, UUID selfId) {
        options.findByGroupIdAndCodeIgnoreCase(group.id(), code.trim()).ifPresent(existing -> {
            if (selfId == null || !existing.id().equals(selfId)) {
                throw new ConflictException(DUPLICATE_OPTION,
                        "Option code already exists in this group: " + code);
            }
        });
    }

    private void requireVersion(long current, Long expected, String code) {
        if (!Objects.equals(expected, current)) {
            throw new ConflictException(code, "This row was updated by another user. Refresh before editing again.");
        }
    }

    private CustomizationGroup requireGroup(UUID groupId) {
        return groups.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException(GROUP_NOT_FOUND, "Customization group not found"));
    }

    private CustomizationOption requireOption(UUID optionId) {
        return options.findById(optionId)
                .orElseThrow(() -> new ResourceNotFoundException(OPTION_NOT_FOUND, "Customization option not found"));
    }

    // ------------------------------------------------------------- projection

    private CustomizationGroupMasterResponse toResponse(CustomizationGroup group) {
        List<CustomizationOptionMasterResponse> optionViews =
                options.findByGroupIdOrderByDisplayOrderAscNameAsc(group.id()).stream()
                        .map(this::toResponse).toList();
        return new CustomizationGroupMasterResponse(group.id(), group.code(), group.name(),
                group.selectionType().name(), group.minimumSelections(), group.maximumSelections(),
                group.displayOrder(), group.isActive(), group.version(), optionViews);
    }

    private CustomizationOptionMasterResponse toResponse(CustomizationOption option) {
        return new CustomizationOptionMasterResponse(option.id(), option.code(), option.name(),
                option.additionalPrice(), option.displayOrder(), option.active(), option.version(),
                variantMapper.toEffectResponse(option));
    }

    private void audit(String action, CurrentActor actor, UUID groupId, Object before, Object after, String note) {
        audit.success(actor.userId(), null, action, "CATALOG", "CUSTOMIZATION_GROUP", groupId, before, after, note);
    }
}
