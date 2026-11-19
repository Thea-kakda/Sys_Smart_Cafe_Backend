package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.InventoryEffectResponse;
import com.smartcoffee.modules.catalog.api.request.SetInventoryEffectRequest;
import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import com.smartcoffee.modules.catalog.persistence.CustomizationOptionRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write path for the inventory-effect configuration on a customization option.
 * Enforces:
 * <ol>
 *   <li>{@code option-effect:manage} permission.</li>
 *   <li>Option existence (404 CUSTOMIZATION_OPTION_NOT_FOUND).</li>
 *   <li>Optimistic-lock version echo (409 OPTION_VERSION_CONFLICT).</li>
 *   <li>Type-conditional shape via {@link CustomizationOption#applyInventoryEffect}
 *       (mirrors ck_option_effect_shape so a caller who would violate the DB
 *       CHECK receives a stable 400 with a business error code instead of a
 *       DataIntegrityViolation).</li>
 *   <li>REPLACE rejects a source option id equal to this option id
 *       (SOURCE_AND_TARGET_ITEM_SAME).</li>
 *   <li>REPLACE requires the referenced source option to exist and to declare
 *       an ingredient of its own; ambiguity is rejected.</li>
 * </ol>
 */
@Service
public class OptionInventoryEffectCommandService {

    private final CustomizationOptionRepository options;
    private final PermissionEvaluator permissions;
    private final ProductVariantMapper mapper;

    public OptionInventoryEffectCommandService(CustomizationOptionRepository options,
            PermissionEvaluator permissions, ProductVariantMapper mapper) {
        this.options = options;
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Transactional
    // The legacy replacesOptionId component is still written through on purpose:
    // old clients may echo it back, and the domain keeps it for read compatibility.
    // javac flags reading that deprecated record component, so the suppression stays.
    @SuppressWarnings("deprecation")
    public InventoryEffectResponse set(CurrentActor actor, UUID optionId, Long version, SetInventoryEffectRequest req) {
        permissions.require(actor, PermissionCodes.OPTION_EFFECT_MANAGE);
        CustomizationOption o = options.findById(optionId)
                .orElseThrow(() -> new ResourceNotFoundException("CUSTOMIZATION_OPTION_NOT_FOUND", "Option not found"));
        if (version != null && !Objects.equals(version, o.version())) {
            throw new com.smartcoffee.common.exception.ConflictException("OPTION_VERSION_CONFLICT",
                    "This option was updated by another user. Refresh before editing again.");
        }
        InventoryEffectType type = req.type() == null ? InventoryEffectType.NONE : req.type();
        if (type == InventoryEffectType.REMOVE && req.sourceIngredientId() == null) {
            throw new ValidationException("SOURCE_INVENTORY_ITEM_REQUIRED",
                    "REMOVE requires a source ingredient.");
        }
        if (type == InventoryEffectType.MULTIPLY && req.sourceIngredientId() == null) {
            throw new ValidationException("SOURCE_INVENTORY_ITEM_REQUIRED",
                    "MULTIPLY requires a source ingredient.");
        }
        if (type == InventoryEffectType.MULTIPLY && req.effectMultiplier() == null) {
            throw new ValidationException("MULTIPLIER_REQUIRED",
                    "MULTIPLY requires an effect_multiplier.");
        }
        if (type == InventoryEffectType.ADD && req.targetIngredientId() == null) {
            throw new ValidationException("TARGET_INVENTORY_ITEM_REQUIRED",
                    "ADD requires a target ingredient.");
        }
        if (type == InventoryEffectType.REPLACE
                && (req.sourceIngredientId() == null || req.targetIngredientId() == null)) {
            throw new ValidationException("INVALID_INVENTORY_EFFECT",
                    "REPLACE requires both a source and a target ingredient.");
        }
        if (type == InventoryEffectType.REPLACE
                && req.sourceIngredientId() != null
                && req.sourceIngredientId().equals(req.targetIngredientId())) {
            throw new ValidationException("SOURCE_AND_TARGET_ITEM_SAME",
                    "REPLACE source and target ingredients must differ.");
        }
        try {
            o.applyInventoryEffect(type, req.sourceIngredientId(), req.targetIngredientId(),
                    req.effectQuantity(), req.effectMultiplier(), req.replacesOptionId());
        } catch (IllegalArgumentException shape) {
            throw new ValidationException("INVALID_INVENTORY_EFFECT", shape.getMessage());
        }
        options.saveAndFlush(o);
        return mapper.toEffectResponse(o);
    }

    @Transactional
    public InventoryEffectResponse clear(CurrentActor actor, UUID optionId, Long version) {
        return set(actor, optionId, version,
                new SetInventoryEffectRequest(InventoryEffectType.NONE, null, null, null, null, null));
    }
}
