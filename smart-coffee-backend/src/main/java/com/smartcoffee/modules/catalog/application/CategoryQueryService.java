package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.modules.catalog.api.CategoryResponse;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryQueryService {
    private final ProductCategoryRepository categories;
    private final CatalogImageUrlResolver images;

    public CategoryQueryService(ProductCategoryRepository categories, CatalogImageUrlResolver images) {
        this.categories = categories;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categories.findByActiveTrueOrderByDisplayOrderAscNameAsc().stream()
                .map(c -> new CategoryResponse(c.id(), c.parentCategoryId(), c.code(), c.name(), c.description(),
                        images.resolve(c.imageObjectKey()), c.displayOrder()))
                .toList();
    }
}

