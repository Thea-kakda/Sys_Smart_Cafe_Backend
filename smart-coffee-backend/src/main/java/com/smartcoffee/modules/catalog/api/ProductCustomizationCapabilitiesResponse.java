package com.smartcoffee.modules.catalog.api;

/**
 * Declares, on every management read, which per-product customization fields
 * the server can actually persist. The Dashboard must consult this BEFORE
 * sending a write: Slice 4A Round 1 stores only {@code required} and
 * {@code displayOrder} on the {@code catalog.product_customization_groups} row
 * (V003 gave it four columns and no version), so selection limits are inherited
 * from the master group and per-product defaults / allowed-option subsets have
 * no storage. Those fields are answered with a stable HTTP 400 rather than
 * silently dropped, and V049 (Round 2) flips these flags by adding the columns.
 *
 * @param supportsRequiredOverride           {@code required} is a real column on the attachment row.
 * @param supportsDisplayOrderOverride       {@code display_order} is a real column on the attachment row.
 * @param supportsMinimumSelectionsOverride  no per-product min column exists (inherited from the master group).
 * @param supportsMaximumSelectionsOverride  no per-product max column exists (inherited from the master group).
 * @param supportsDefaultOptionOverride      no per-product default column exists at all.
 * @param supportsAllowedOptionSubset        no product/option allow-list table exists; the effective
 *                                           allow-list is every active option of the master group.
 * @param relationshipVersionSource          which entity carries the optimistic-lock token. {@code PRODUCT}
 *                                           means {@code relationshipVersion} is the owning
 *                                           {@code Product.version}, not an independent relationship version.
 */
public record ProductCustomizationCapabilitiesResponse(
        boolean supportsRequiredOverride,
        boolean supportsDisplayOrderOverride,
        boolean supportsMinimumSelectionsOverride,
        boolean supportsMaximumSelectionsOverride,
        boolean supportsDefaultOptionOverride,
        boolean supportsAllowedOptionSubset,
        String relationshipVersionSource) {

    /** The one Round 1 capability set, kept in a single place so no response can drift from another. */
    public static ProductCustomizationCapabilitiesResponse roundOne() {
        return new ProductCustomizationCapabilitiesResponse(
                true, true, false, false, false, false, ProductCustomizationCapabilitiesResponse.PRODUCT_SOURCE);
    }

    /**
     * The Round 2 capability set, live once V049 lands the override columns and
     * the product/option allow-list table. Every per-product field the editor can
     * now set is backed by real storage, and the optimistic-lock token is the
     * attachment row's own {@code version} rather than the owning product's.
     */
    public static ProductCustomizationCapabilitiesResponse roundTwo() {
        return new ProductCustomizationCapabilitiesResponse(
                true, true, true, true, true, true, ProductCustomizationCapabilitiesResponse.RELATIONSHIP_SOURCE);
    }

    /** Lock token carrier: the owning product aggregate. */
    public static final String PRODUCT_SOURCE = "PRODUCT";

    /** Lock token carrier: the {@code product_customization_groups} attachment row (V049 on). */
    public static final String RELATIONSHIP_SOURCE = "RELATIONSHIP";
}
