package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.modules.catalog.domain.CustomizationGroup;
import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.ProductCustomizationGroup;
import com.smartcoffee.modules.catalog.domain.ProductCustomizationOption;
import com.smartcoffee.modules.catalog.persistence.CustomizationGroupRepository;
import com.smartcoffee.modules.catalog.persistence.CustomizationOptionRepository;
import com.smartcoffee.modules.catalog.persistence.ProductCustomizationGroupRepository;
import com.smartcoffee.modules.catalog.persistence.ProductCustomizationOptionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one effective-customization implementation (§4). Loads, for a product: the
 * V049 attachment override rows, the V049 option allow-list rows, and the master
 * group/option rows, then merges them into {@link EffectiveCustomization.Group}
 * values. POS/Customer detail, the cart validator, the availability evaluator
 * and the consumption planner all delegate here so a Dashboard-configured
 * override is honoured identically across every read path — never re-derived a
 * second time in a consumer.
 *
 * <p><b>Backward-compatibility rule.</b> When a group attachment has NO
 * allow-list rows (an attachment made by the Round 1 code path, or one whose
 * rows were removed outside the product flow), the effective set falls back to
 * <em>every active master option</em>, exactly as the pre-V049 readers did. This
 * keeps existing POS/Customer/Cart/Availability behaviour byte-for-byte unchanged
 * for data that predates Round 2 while still honouring an explicit subset once
 * one is stored.
 *
 * <p><b>Inheritance.</b> Effective minimum/maximum = the attachment override when
 * present, else the master value. Effective price = the allow-list
 * {@code price_override} when present, else the master {@code additional_price}.
 * Effective option order = the allow-list {@code display_order} when present,
 * else the master order. Only options that are both allowed and master-active are
 * ever returned.
 */
@Service
public class EffectiveCustomizationResolver {

    private final ProductCustomizationGroupRepository attachments;
    private final ProductCustomizationOptionRepository optionAttachments;
    private final CustomizationGroupRepository groups;
    private final CustomizationOptionRepository options;

    public EffectiveCustomizationResolver(ProductCustomizationGroupRepository attachments,
            ProductCustomizationOptionRepository optionAttachments, CustomizationGroupRepository groups,
            CustomizationOptionRepository options) {
        this.attachments = attachments;
        this.optionAttachments = optionAttachments;
        this.groups = groups;
        this.options = options;
    }

    /**
     * Customer/POS-effective configuration: attached groups whose master row is
     * still active, each with its effective allowed options. Groups whose master
     * was deactivated are omitted (they are only surfaced on the management read,
     * which deliberately shows an attached-but-inactive group so it can be
     * detached).
     */
    @Transactional(readOnly = true)
    public List<EffectiveCustomization.Group> resolve(UUID productId) {
        return resolve(productId, false);
    }

    /**
     * @param includeInactiveGroups when {@code true}, groups with an inactive
     *        master are returned as well (flagged via {@code groupActive}); used
     *        by the management projection so an editor sees what is really on the
     *        attachment row instead of silently losing it.
     */
    @Transactional(readOnly = true)
    public List<EffectiveCustomization.Group> resolve(UUID productId, boolean includeInactiveGroups) {
        List<ProductCustomizationGroup> attachmentRows = attachments.findAllForProduct(productId);
        if (attachmentRows.isEmpty()) {
            return List.of();
        }
        List<UUID> groupIds = attachmentRows.stream().map(attachment -> attachment.groupId()).toList();
        Map<UUID, CustomizationGroup> masterById = new LinkedHashMap<>();
        groups.findAllById(groupIds).forEach(g -> masterById.put(g.id(), g));

        // One query for the whole product allow-list, bucketed per group.
        Map<UUID, List<ProductCustomizationOption>> allowByGroup = new LinkedHashMap<>();
        for (ProductCustomizationOption row : optionAttachments.findByProductIdAndActiveTrue(productId)) {
            allowByGroup.computeIfAbsent(row.groupId(), k -> new ArrayList<>()).add(row);
        }

        List<EffectiveCustomization.Group> result = new ArrayList<>();
        for (ProductCustomizationGroup attachment : attachmentRows) {
            CustomizationGroup master = masterById.get(attachment.groupId());
            if (master == null) {
                // FK guarantees existence; a missing master means out-of-band DB change.
                continue;
            }
            if (!master.isActive() && !includeInactiveGroups) {
                continue;
            }
            int effectiveMin = attachment.minimumSelections() != null
                    ? attachment.minimumSelections() : master.minimumSelections();
            Integer effectiveMax = attachment.maximumSelections() != null
                    ? attachment.maximumSelections() : master.maximumSelections();
            List<EffectiveCustomization.Option> options = effectiveOptions(attachment, master,
                    allowByGroup.getOrDefault(master.id(), List.of()));
            result.add(new EffectiveCustomization.Group(master.id(), master.code(), master.name(),
                    master.selectionType().name(), master.isActive(), attachment.required(), effectiveMin,
                    effectiveMax, attachment.defaultOptionId(), attachment.displayOrder(), attachment.version(),
                    options));
        }
        return result;
    }

    private List<EffectiveCustomization.Option> effectiveOptions(ProductCustomizationGroup attachment,
            CustomizationGroup master, List<ProductCustomizationOption> allowRows) {
        Map<UUID, CustomizationOption> activeMasterOptions = new LinkedHashMap<>();
        for (CustomizationOption o : options.findByGroupIdAndActiveTrueOrderByDisplayOrderAscNameAsc(master.id())) {
            activeMasterOptions.put(o.id(), o);
        }
        List<EffectiveCustomization.Option> out = new ArrayList<>();
        if (allowRows.isEmpty()) {
            // Fallback: no stored subset — every active master option, master order and price.
            for (CustomizationOption o : activeMasterOptions.values()) {
                out.add(toOption(o, null, o.displayOrder(), attachment));
            }
        } else {
            for (ProductCustomizationOption row : allowRows) {
                if (!row.allowed()) {
                    continue;
                }
                CustomizationOption master2 = activeMasterOptions.get(row.optionId());
                if (master2 == null) {
                    // Allowed but the master option is now inactive/not in the group — drop it.
                    continue;
                }
                int order = row.displayOrder();
                out.add(toOption(master2, row, order, attachment));
            }
        }
        out.sort(Comparator.comparingInt((EffectiveCustomization.Option option) -> option.displayOrder())
                .thenComparing((EffectiveCustomization.Option option) -> option.name()));
        return out;
    }

    private EffectiveCustomization.Option toOption(CustomizationOption master, ProductCustomizationOption row,
            int displayOrder, ProductCustomizationGroup attachment) {
        var priceOverride = row == null ? null : row.priceOverride();
        var effectivePrice = priceOverride != null ? priceOverride : master.additionalPrice();
        boolean isDefault = attachment.defaultOptionId() != null && attachment.defaultOptionId().equals(master.id());
        return new EffectiveCustomization.Option(master.id(), master.code(), master.name(), master.active(),
                master.additionalPrice(), priceOverride, effectivePrice, displayOrder, isDefault,
                master.inventoryEffectType(), master.sourceIngredientId(), master.targetIngredientId(),
                master.effectQuantity(), master.effectMultiplier(), master.replacesOptionId(), master.version());
    }
}
