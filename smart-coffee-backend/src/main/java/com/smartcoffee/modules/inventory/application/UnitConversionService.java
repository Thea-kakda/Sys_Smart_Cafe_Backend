package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.*;
import com.smartcoffee.modules.inventory.domain.*;
import com.smartcoffee.modules.inventory.persistence.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnitConversionService {
    private final UnitOfMeasureRepository units;
    private final IngredientRepository ingredients;

    public UnitConversionService(UnitOfMeasureRepository u, IngredientRepository i) {
        units = u;
        ingredients = i;
    }

    @Transactional(readOnly = true)
    public BigDecimal toIngredientBase(UUID ingredientId, String submittedUnit, BigDecimal quantity) {
        Ingredient ingredient = ingredients.findByIdAndActiveTrue(ingredientId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingredient not found"));
        UnitOfMeasure source = units.findByCodeAndActiveTrue(submittedUnit.toUpperCase())
                .orElseThrow(() -> new ValidationException("Unknown unit of measure"));
        UnitOfMeasure base = units.findById(ingredient.baseUnitId())
                .orElseThrow(() -> new InfrastructureException("Ingredient base unit missing"));
        if (source.type() != base.type())
            throw new ValidationException("Unit type is incompatible with ingredient");
        return source.toBase(quantity).setScale(base.decimalScale(), java.math.RoundingMode.HALF_UP);
    }
}

