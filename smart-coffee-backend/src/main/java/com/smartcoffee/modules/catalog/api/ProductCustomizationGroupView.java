package com.smartcoffee.modules.catalog.api;

import java.util.List;
import java.util.UUID;

/**
 * Effective customization-group configuration for one product, flattened from
 * the two rows it is composed of:
 *
 * <ul>
 *   <li><b>per-product (writable in Round 1)</b> — {@code required} and
 *       {@code displayOrder} come from the
 *       {@code catalog.product_customization_groups} attachment row;</li>
 *   <li><b>inherited (read-only in Round 1)</b> — {@code selectionType},
 *       {@code minimumSelections} and {@code maximumSelections} come from the
 *       master {@code catalog.customization_groups} row and therefore apply to
 *       every product that attaches the group. A client that echoes them back
 *       unchanged is accepted; a client that tries to change them gets
 *       {@code 400 PRODUCT_SELECTION_LIMIT_OVERRIDE_NOT_SUPPORTED};</li>
 *   <li>{@code allowedOptions} is the <em>effective</em> allow-list, i.e. every
 *       active option of the master group — not a per-product subset, which has
 *       no storage until V049;</li>
 *   <li>{@code defaultOptionId} is always {@code null} for the same reason;</li>
 *   <li>{@code relationshipVersion} is the owning {@code Product.version}. The
 *       attachment table has no version column, so the product aggregate is the
 *       lock token for the whole child collection — it is not an independent
 *       relationship version;</li>
 *   <li>{@code relationshipId} is {@code null} because the attachment is
 *       identified by the composite {@code (productId, groupId)} key. Use that
 *       pair, never the null id.</li>
 * </ul>
 */
public record ProductCustomizationGroupView(
        UUID relationshipId,
        UUID productId,
        UUID groupId,
        String groupCode,
        String groupName,
        String selectionType,
        boolean groupActive,
        boolean required,
        int displayOrder,
        int minimumSelections,
        Integer maximumSelections,
        UUID defaultOptionId,
        long relationshipVersion,
        List<ProductCustomizationOptionView> allowedOptions) {

    public ProductCustomizationGroupView {
        allowedOptions = List.copyOf(allowedOptions);
    }
}
