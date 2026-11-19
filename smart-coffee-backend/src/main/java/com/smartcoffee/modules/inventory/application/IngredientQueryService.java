package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.inventory.api.IngredientResponse;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
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
 * Ingredient master reads for the Dashboard management view (M2), guarded by
 * {@code inventory:read}. Paginated search over code/name (blank lists all) and
 * single detail, both enriched with base-unit labels. Writes live in
 * {@link IngredientCommandService}.
 */
@SuppressWarnings("null")
@Service
public class IngredientQueryService {

    private final IngredientRepository ingredients;
    private final UnitOfMeasureRepository units;
    private final PermissionEvaluator permissions;

    public IngredientQueryService(IngredientRepository ingredients, UnitOfMeasureRepository units,
            PermissionEvaluator permissions) {
        this.ingredients = ingredients;
        this.units = units;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public Page<IngredientResponse> search(CurrentActor actor, String query, int page, int size) {
        permissions.require(actor, PermissionCodes.INVENTORY_READ);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String q = query == null ? "" : query.trim();
        Page<Ingredient> result = q.isEmpty()
                ? ingredients.findAll(PageRequest.of(safePage, safeSize,
                        org.springframework.data.domain.Sort.by("code")))
                : ingredients.search(q, PageRequest.of(safePage, safeSize));
        Map<UUID, UnitOfMeasure> unitMap = loadUnits(result.getContent());
        return result.map(i -> IngredientCommandService.toResponse(i, unitMap.get(i.baseUnitId())));
    }

    @Transactional(readOnly = true)
    public IngredientResponse get(CurrentActor actor, UUID ingredientId) {
        permissions.require(actor, PermissionCodes.INVENTORY_READ);
        Ingredient i = ingredients.findById(ingredientId)
                .orElseThrow(() -> new ResourceNotFoundException("INGREDIENT_NOT_FOUND", "Ingredient not found"));
        UnitOfMeasure unit = units.findById(i.baseUnitId()).orElse(null);
        return IngredientCommandService.toResponse(i, unit);
    }

    private Map<UUID, UnitOfMeasure> loadUnits(List<Ingredient> list) {
        List<UUID> unitIds = list.stream().map(Ingredient::baseUnitId).distinct().collect(Collectors.toList());
        if (unitIds.isEmpty()) {
            return Map.of();
        }
        return units.findAllById(unitIds).stream().collect(Collectors.toMap(UnitOfMeasure::id, Function.identity()));
    }
}
