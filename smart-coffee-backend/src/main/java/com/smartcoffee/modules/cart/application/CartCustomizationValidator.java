package com.smartcoffee.modules.cart.application;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import com.smartcoffee.modules.cart.domain.CartItemCustomization;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CartCustomizationValidator {
    public List<CartItemCustomization> validate(List<CartCatalogGateway.GroupRule> rules,
            List<SelectedCustomizationRequest> selected) {
        List<SelectedCustomizationRequest> safe = selected == null ? List.of() : selected;
        Map<UUID, List<SelectedCustomizationRequest>> byGroup = new HashMap<>();
        for (var s : safe) {
            if (s.quantity() <= 0)
                throw new ValidationException("Customization quantity must be positive");
            byGroup.computeIfAbsent(s.groupId(), x -> new ArrayList<>()).add(s);
        }
        Set<UUID> validGroups = new HashSet<>();
        List<CartItemCustomization> result = new ArrayList<>();
        for (var rule : rules) {
            validGroups.add(rule.groupId());
            var requests = byGroup.getOrDefault(rule.groupId(), List.of());
            int selections = 0;
            for (var request : requests)
                selections += request.quantity();
            if (selections < rule.minimum())
                throw new ValidationException("Required customization is missing: " + rule.groupName());
            if (rule.maximum() != null && selections > rule.maximum())
                throw new ValidationException("Too many selections for: " + rule.groupName());
            if ("SINGLE".equals(rule.selectionType()) && requests.size() > 1)
                throw new ValidationException("Only one option is allowed for: " + rule.groupName());
            Map<UUID, CartCatalogGateway.OptionRule> available = new HashMap<>();
            rule.options().forEach(o -> available.put(o.optionId(), o));
            Set<UUID> duplicates = new HashSet<>();
            for (var request : requests) {
                if (!duplicates.add(request.optionId()))
                    throw new ValidationException("Duplicate customization option");
                var option = available.get(request.optionId());
                if (option == null || !option.active())
                    throw new ValidationException("Invalid customization option for: " + rule.groupName());
                result.add(new CartItemCustomization(rule.groupId(), option.optionId(), rule.groupName(),
                        option.optionName(), option.additionalPrice(), request.quantity()));
            }
        }
        for (UUID groupId : byGroup.keySet())
            if (!validGroups.contains(groupId))
                throw new ValidationException("Customization group does not belong to product");
        return result;
    }
}

