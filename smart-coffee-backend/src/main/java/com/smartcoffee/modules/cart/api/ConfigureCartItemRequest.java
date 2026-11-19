package com.smartcoffee.modules.cart.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

/**
 * Reconfiguration of an existing cart line (Slice 2C §9 D/E): swap the sellable
 * variant and/or the customization selection while keeping the line's quantity.
 *
 * <p>Both fields are optional — {@code null} means "no change" for the variant
 * and "empty selection" for the customizations, which is validated against the
 * product's catalog rules exactly like an add. The inventory consequence is a
 * per-ingredient delta, never a release-and-re-reserve cycle.
 */
public record ConfigureCartItemRequest(UUID variantId,
        List<@Valid SelectedCustomizationRequest> customizations) {
}
