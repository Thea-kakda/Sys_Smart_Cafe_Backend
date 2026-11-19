package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.modules.catalog.api.ProductDetailsResponse;
import com.smartcoffee.modules.catalog.api.ProductSummaryResponse;
import com.smartcoffee.modules.catalog.domain.Channel;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * Backward-compatible facade over the shared {@link ChannelCatalogService}
 * (Slice 3B). The existing public method signatures are preserved (the customer
 * catalog integration tests and legacy callers depend on them) and default to the
 * anonymous {@link Channel#CUSTOMER_WEB} surface. HTTP callers that need an
 * authenticated/POS channel go through {@code StoreCatalogController}, which
 * resolves the trusted channel and calls {@link ChannelCatalogService} directly.
 */
@Service
public class StoreCatalogQueryService {
    private final ChannelCatalogService catalog;

    public StoreCatalogQueryService(ChannelCatalogService catalog) {
        this.catalog = catalog;
    }

    public Page<ProductSummaryResponse> list(UUID storeId, String search, UUID categoryId, boolean availableOnly,
            int page, int size) {
        return catalog.list(storeId, Channel.CUSTOMER_WEB, search, categoryId, availableOnly, page, size);
    }

    public ProductDetailsResponse details(UUID storeId, UUID productId) {
        return catalog.detail(storeId, Channel.CUSTOMER_WEB, productId);
    }
}
