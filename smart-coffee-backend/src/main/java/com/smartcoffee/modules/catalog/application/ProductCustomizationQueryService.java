package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.modules.catalog.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer/POS-facing customization read. Round 2 delegates entirely to the
 * {@link EffectiveCustomizationResolver} so the same per-product overrides the
 * Dashboard writes (allowed subset, selection limits, price overrides) are the
 * values a customer or POS actually sees. Before this, the query read the master
 * group's limits and every active option directly, ignoring product overrides.
 *
 * <p>Slice 4B: the per-product default is projected through the SAME resolver
 * source — {@code defaultOptionId} from the attachment row and {@code isDefault}
 * per option — so POS/Customer honour only a backend-declared default and never
 * infer one. Both fields are additive; consumers that ignore them are unaffected.
 */
@Service
public class ProductCustomizationQueryService {
    private final EffectiveCustomizationResolver resolver;

    public ProductCustomizationQueryService(EffectiveCustomizationResolver resolver) {
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    public List<CustomizationGroupResponse> forProduct(UUID productId) {
        return resolver.resolve(productId).stream().map(g -> {
            var opts = g.options().stream()
                    .map(o -> new CustomizationOptionResponse(o.optionId(), o.code(), o.name(), o.effectivePrice(),
                            o.masterActive(), o.isDefault()))
                    .toList();
            return new CustomizationGroupResponse(g.groupId(), g.code(), g.name(), g.selectionType(),
                    g.minimumSelections() > 0, g.minimumSelections(), g.maximumSelections(), opts,
                    g.defaultOptionId());
        }).toList();
    }
}
