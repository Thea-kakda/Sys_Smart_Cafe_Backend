package com.smartcoffee.modules.cart.api;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record SelectedCustomizationRequest(@NotNull UUID groupId, @NotNull UUID optionId, @Min(1) int quantity) {
}

