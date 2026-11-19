package com.smartcoffee.modules.cart.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public record AddCartItemRequest(@NotNull UUID storeId, @NotNull UUID productId, UUID variantId,
        @Min(1) @Max(99) int quantity, @Size(max = 500) String note,
        List<@Valid SelectedCustomizationRequest> customizations) {
}
