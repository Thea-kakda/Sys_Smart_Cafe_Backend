package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Attach-or-update payload for
 * {@code PUT /api/v1/management/catalog/products/{productId}/customization-groups/{groupId}}.
 *
 * <p><b>Round 2 (V049) writes.</b> After V049 the attachment row and its
 * {@code product_customization_options} allow-list can store the whole editor
 * contract, so every field below is now a real write instead of an echo-only
 * forward declaration: {@code required}, {@code displayOrder}, the
 * {@code minimumSelections}/{@code maximumSelections} per-product overrides (a
 * {@code null} means "inherit the master group"), {@code defaultOptionId}, and
 * the per-option {@code options} set (allowed flag, price override, order). The
 * {@code allowedOptionIds} shortcut is retained for callers that only manage the
 * membership of the subset and send no per-option fields.
 *
 * <p><b>Controlled diff.</b> {@code options} / {@code allowedOptionIds} replace
 * the effective subset by diffing against the stored rows — options dropped from
 * the set are flagged {@code allowed = false} (their history and any later price
 * override are preserved, never delete-and-reinsert), new ones are added, kept
 * ones updated in place. Sending neither field leaves the existing subset
 * untouched.
 *
 * <p><b>Locking.</b> From V049 the relationship carries its own optimistic-lock
 * token: {@code relationshipVersion} is the {@code product_customization_groups}
 * row {@code version} ({@code capabilities.relationshipVersionSource =
 * RELATIONSHIP}), and a stale value is a 409
 * {@code PRODUCT_CUSTOMIZATION_GROUP_VERSION_CONFLICT}. {@code
 * expectedProductVersion} remains accepted as the collection-level token and,
 * when supplied, must still match the owning {@code Product.version}.
 */
public record ConfigureProductCustomizationGroupRequest(
        Boolean required,
        @PositiveOrZero Integer displayOrder,
        Integer minimumSelections,
        Integer maximumSelections,
        List<UUID> allowedOptionIds,
        List<OptionOverride> options,
        UUID defaultOptionId,
        @NotNull Long expectedProductVersion,
        Long relationshipVersion) {

    public boolean requiredOrDefault() {
        return Boolean.TRUE.equals(required);
    }

    public int displayOrderOrDefault() {
        return displayOrder == null ? 0 : displayOrder;
    }

    /**
     * One option's per-product configuration inside the allow-list.
     *
     * @param optionId      the master option being configured (must belong to this group).
     * @param allowed       {@code false} drops the option from the effective subset; {@code null}/{@code true} keeps it.
     * @param priceOverride per-product add-on price; {@code null} inherits the master {@code additional_price}.
     * @param displayOrder  per-product ordering of this option; {@code null} keeps the stored/master order.
     */
    public record OptionOverride(UUID optionId, Boolean allowed, BigDecimal priceOverride, Integer displayOrder) {}
}
