package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.ManagementCategoryResponse;
import com.smartcoffee.modules.catalog.domain.ProductCategory;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Docker-free regression coverage for the protected management category read.
 *
 * <p>Guards against the HTTP 500 that occurred when every seeded category is
 * top-level: {@code parentNames} short-circuits to an immutable {@code Map.of()}
 * and the mapping used to call {@code names.get(null)}, which {@code Map.of()}
 * rejects with a NullPointerException. The fix mirrors the null-parent guard in
 * {@code get()}, so a flat roster must list cleanly with null parent names.
 */
class CategoryManagementQueryServiceTest {

    private final ProductCategoryRepository categories = mock(ProductCategoryRepository.class);
    private final PermissionEvaluator permissions = mock(PermissionEvaluator.class);
    private final CatalogImageUrlResolver images = key -> null;
    private final CatalogManagementMapper mapper = new CatalogManagementMapper(images);
    private final CategoryManagementQueryService service =
            new CategoryManagementQueryService(categories, mapper, permissions);

    private final CurrentActor actor = new CurrentActor(
            UUID.randomUUID(), "USER", Set.of(PermissionCodes.CATEGORY_MANAGE), Set.of(), true);

    @Test
    void listSucceedsWhenAllCategoriesAreTopLevel() {
        // The exact production shape: several categories, none with a parent.
        ProductCategory drinks = ProductCategory.create(null, "DRINKS", "Drinks", null, 1, true, null);
        ProductCategory bakery = ProductCategory.create(null, "BAKERY", "Bakery", null, 2, true, null);
        when(categories.findAllByOrderByDisplayOrderAscNameAsc()).thenReturn(List.of(drinks, bakery));

        List<ManagementCategoryResponse> out = assertDoesNotThrow(() -> service.list(actor));

        assertEquals(2, out.size());
        assertNull(out.get(0).parentName(), "top-level category has no parent name");
        assertNull(out.get(1).parentName());
        assertEquals("DRINKS", out.get(0).code());
    }

    @Test
    void listResolvesParentNameWhenPresent() {
        ProductCategory parent = ProductCategory.create(null, "DRINKS", "Drinks", null, 1, true, null);
        ProductCategory child = ProductCategory.create(parent.id(), "ESPRESSO", "Espresso", null, 1, true, null);
        when(categories.findAllByOrderByDisplayOrderAscNameAsc()).thenReturn(List.of(parent, child));
        when(categories.findAllById(any())).thenReturn(List.of(parent));

        List<ManagementCategoryResponse> out = service.list(actor);

        assertEquals(2, out.size());
        assertNull(out.get(0).parentName());
        assertNotNull(out.get(1).parentName());
        assertEquals("Drinks", out.get(1).parentName());
    }
}
