package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.ManagementProductResponse;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protected product-management reads for the Dashboard catalog editor. Exposes
 * the raw {@code imageObjectKey} and optimistic-lock {@code version} the public
 * catalog DTOs deliberately omit, and resolves a short-lived presigned
 * {@code imageUrl}. Guarded by {@code product:manage} (same capability as the
 * write path) and never changes the public browsing contract.
 */
@SuppressWarnings("null")
@Service
public class ProductManagementQueryService {

    private final ProductRepository products;
    private final ProductCategoryRepository categories;
    private final CatalogManagementMapper mapper;
    private final PermissionEvaluator permissions;

    public ProductManagementQueryService(ProductRepository products, ProductCategoryRepository categories,
            CatalogManagementMapper mapper, PermissionEvaluator permissions) {
        this.products = products;
        this.categories = categories;
        this.mapper = mapper;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public Page<ManagementProductResponse> list(CurrentActor actor, String search, UUID categoryId,
            String sort, String direction, int page, int size) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        String q = (search == null || search.isBlank()) ? "" : search.trim();
        Page<Product> result = products.search(q, categoryId, pageable(sort, direction, page, size));
        Map<UUID, String> names = categoryNames(result.getContent());
        return result.map(p -> mapper.toProduct(p, names.get(p.categoryId())));
    }

    @Transactional(readOnly = true)
    public ManagementProductResponse get(CurrentActor actor, UUID productId) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        Product product = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
        String categoryName = categories.findById(product.categoryId()).map(c -> c.name()).orElse(null);
        return mapper.toProduct(product, categoryName);
    }

    /** Batch-resolves category names for a page to avoid an N+1 lookup per row. */
    private Map<UUID, String> categoryNames(List<Product> rows) {
        List<UUID> ids = rows.stream().map(Product::categoryId).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categories.findAllById(ids).stream()
                .collect(Collectors.toMap(c -> c.id(), c -> c.name(), (a, b) -> a));
    }

    private Pageable pageable(String sort, String direction, int page, int size) {
        String field = (sort == null || sort.isBlank()) ? "name" : switch (sort.trim()) {
            case "code" -> "code";
            case "name" -> "name";
            case "basePrice" -> "basePrice";
            case "updatedAt" -> "updatedAt";
            default -> "name";
        };
        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(dir, field));
    }
}
