package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.modules.catalog.api.ManagementCategoryResponse;
import com.smartcoffee.modules.catalog.api.ManagementProductResponse;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductCategory;
import org.springframework.stereotype.Component;

/**
 * Builds the management read/write projections from the catalog entities. The
 * raw {@code imageObjectKey} is passed through untouched, and the display
 * {@code imageUrl} is resolved through {@link CatalogImageUrlResolver} (a local
 * presign, no bucket/credential leakage). Names ({@code categoryName},
 * {@code parentName}) are supplied by callers so the mapping stays a pure
 * function and list reads can batch the lookups instead of triggering N+1
 * queries.
 */
@Component
public class CatalogManagementMapper {

    private final CatalogImageUrlResolver images;

    public CatalogManagementMapper(CatalogImageUrlResolver images) {
        this.images = images;
    }

    public ManagementProductResponse toProduct(Product p, String categoryName) {
        return new ManagementProductResponse(
                p.id(), p.code(), p.categoryId(), categoryName, p.name(), p.description(),
                p.basePrice(), p.currencyCode(), p.preparationMinutes(), p.taxRate(),
                p.customizable(), p.active(),
                p.productType(), p.stockMode(),
                images.resolve(p.imageObjectKey()), p.imageObjectKey(),
                p.version(), p.createdAt(), p.updatedAt());
    }

    public ManagementCategoryResponse toCategory(ProductCategory c, String parentName) {
        return new ManagementCategoryResponse(
                c.id(), c.parentCategoryId(), parentName, c.code(), c.name(), c.description(),
                images.resolve(c.imageObjectKey()), c.imageObjectKey(),
                c.displayOrder(), c.active(), c.version(), c.createdAt(), c.updatedAt());
    }
}
