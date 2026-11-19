package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.api.CreateIngredientRequest;
import com.smartcoffee.modules.inventory.api.IngredientResponse;
import com.smartcoffee.modules.inventory.api.UpdateIngredientRequest;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingredient master write path (create / update / deactivate) — closes the
 * Dashboard's documented ingredient API gap (M2). Guarded by
 * {@code ingredient:manage}. Code uniqueness is checked up front with the
 * UNIQUE constraint as the concurrency backstop ({@code 409 DUPLICATE_CODE});
 * the base unit must exist; updates echo the optimistic-lock {@code version}
 * ({@code 409 VERSION_CONFLICT}). Ingredients are never hard-deleted —
 * {@code active=false} retires them (A3). Store is null in audit: ingredients
 * are a global master.
 */
@Service
public class IngredientCommandService {

    private final IngredientRepository ingredients;
    private final UnitOfMeasureRepository units;
    private final AuditWriter audit;
    private final PermissionEvaluator permissions;

    public IngredientCommandService(IngredientRepository ingredients, UnitOfMeasureRepository units,
            AuditWriter audit, PermissionEvaluator permissions) {
        this.ingredients = ingredients;
        this.units = units;
        this.audit = audit;
        this.permissions = permissions;
    }

    @Transactional
    public IngredientResponse create(CurrentActor actor, CreateIngredientRequest req) {
        permissions.require(actor, PermissionCodes.INGREDIENT_MANAGE);
        String code = req.code().trim();
        if (ingredients.existsByCode(code)) {
            throw new ConflictException("DUPLICATE_CODE", "Ingredient code already exists: " + code);
        }
        requireUnit(req.baseUnitId());
        Ingredient ingredient = Ingredient.create(code, req.name(), req.baseUnitId(), req.standardCost(),
                req.trackInventoryOrDefault(), req.trackBatchesOrFalse(), req.shelfLifeDays(), req.activeOrDefault());
        try {
            ingredients.saveAndFlush(ingredient);
        } catch (DataIntegrityViolationException concurrent) {
            throw new ConflictException("DUPLICATE_CODE", "Ingredient code already exists: " + code);
        }
        IngredientResponse after = toResponse(ingredient);
        audit.success(actor.userId(), null, "INGREDIENT_CREATED", "INVENTORY", "INGREDIENT", ingredient.id(), null,
                after, "Created ingredient " + ingredient.code());
        return after;
    }

    @Transactional
    public IngredientResponse update(CurrentActor actor, UUID ingredientId, UpdateIngredientRequest req) {
        permissions.require(actor, PermissionCodes.INGREDIENT_MANAGE);
        Ingredient ingredient = ingredients.findById(ingredientId)
                .orElseThrow(() -> new ResourceNotFoundException("INGREDIENT_NOT_FOUND", "Ingredient not found"));
        if (!Objects.equals(req.version(), ingredient.version())) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This ingredient was updated by another user. Refresh before editing again.");
        }
        String code = req.code().trim();
        if (ingredients.existsByCodeAndIdNot(code, ingredientId)) {
            throw new ConflictException("DUPLICATE_CODE", "Ingredient code already exists: " + code);
        }
        requireUnit(req.baseUnitId());
        IngredientResponse before = toResponse(ingredient);
        ingredient.updateDetails(code, req.name(), req.baseUnitId(), req.standardCost(), req.trackInventoryOrDefault(),
                req.trackBatchesOrFalse(), req.shelfLifeDays(), req.activeOrDefault());
        try {
            ingredients.saveAndFlush(ingredient);
        } catch (DataIntegrityViolationException concurrent) {
            throw new ConflictException("DUPLICATE_CODE", "Ingredient code already exists: " + code);
        }
        IngredientResponse after = toResponse(ingredient);
        audit.success(actor.userId(), null, "INGREDIENT_UPDATED", "INVENTORY", "INGREDIENT", ingredientId, before,
                after, "Updated ingredient " + ingredient.code());
        return after;
    }

    private void requireUnit(UUID unitId) {
        if (!units.existsById(unitId)) {
            throw new ResourceNotFoundException("UNIT_NOT_FOUND", "Base unit of measure not found");
        }
    }

    static IngredientResponse toResponse(Ingredient i) {
        return toResponse(i, null, null);
    }

    static IngredientResponse toResponse(Ingredient i, UnitOfMeasure unit) {
        return toResponse(i, unit == null ? null : unit.code(), unit == null ? null : unit.name());
    }

    static IngredientResponse toResponse(Ingredient i, String unitCode, String unitName) {
        return new IngredientResponse(i.id(), i.code(), i.name(), i.baseUnitId(), unitCode, unitName,
                i.standardCost(), i.trackInventory(), i.trackBatches(), i.shelfLifeDays(), i.active(), i.createdAt(),
                i.updatedAt(), i.version());
    }
}
