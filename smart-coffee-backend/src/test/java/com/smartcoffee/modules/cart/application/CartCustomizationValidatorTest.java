package com.smartcoffee.modules.cart.application;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class CartCustomizationValidatorTest {
    private final CartCustomizationValidator validator = new CartCustomizationValidator();
    private final UUID group = UUID.randomUUID(), option = UUID.randomUUID();

    private CartCatalogGateway.GroupRule rule() {
        return new CartCatalogGateway.GroupRule(group, "Milk", "SINGLE", 1, 1,
                List.of(new CartCatalogGateway.OptionRule(option, "Oat Milk", new BigDecimal("0.50"), true)));
    }

    @Test
    void acceptsValidRequiredSingleSelection() {
        assertEquals(1, validator.validate(List.of(rule()), List.of(new SelectedCustomizationRequest(group, option, 1)))
                .size());
    }

    @Test
    void rejectsMissingRequiredSelection() {
        assertThrows(ValidationException.class, () -> validator.validate(List.of(rule()), List.of()));
    }

    @Test
    void rejectsOptionFromAnotherGroup() {
        assertThrows(ValidationException.class, () -> validator.validate(List.of(rule()),
                List.of(new SelectedCustomizationRequest(group, UUID.randomUUID(), 1))));
    }
}

