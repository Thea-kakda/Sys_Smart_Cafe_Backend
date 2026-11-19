package com.smartcoffee.modules.cart.api;

import jakarta.validation.constraints.*;

public record UpdateCartItemRequest(@Min(1) @Max(99) int quantity) {
}

