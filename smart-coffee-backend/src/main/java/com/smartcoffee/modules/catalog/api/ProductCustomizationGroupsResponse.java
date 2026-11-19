package com.smartcoffee.modules.catalog.api;

import java.util.List;
import java.util.UUID;

/**
 * Response envelope for
 * {@code GET /api/v1/management/catalog/products/{productId}/customization-groups}.
 *
 * <p>{@code capabilities} sits once at response level (not per group) so a
 * client cannot be told two different things about the same field, and it
 * travels with the data it describes: the Dashboard reads it before rendering
 * the Options editor and must not offer an input the server would reject.
 * {@code productVersion} is repeated at the top as the current lock token for
 * the whole child collection — every attachment shares it because
 * {@code catalog.product_customization_groups} has no version column of its own.
 *
 * @param productId      the product whose attachments are reported.
 * @param productVersion authoritative {@code Product.version} at read time.
 * @param capabilities   which per-product fields Round 1 can actually persist.
 * @param groups         attachments ordered by the per-product display order.
 */
public record ProductCustomizationGroupsResponse(
        UUID productId,
        long productVersion,
        ProductCustomizationCapabilitiesResponse capabilities,
        List<ProductCustomizationGroupView> groups) {

    public ProductCustomizationGroupsResponse {
        groups = List.copyOf(groups);
    }
}
