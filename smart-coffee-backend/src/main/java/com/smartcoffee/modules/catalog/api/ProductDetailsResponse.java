package com.smartcoffee.modules.catalog.api;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
/**
 * Store catalog detail (Slice 3B). All original fields — including the legacy
 * {@code customizationGroups} shape POS/Customer already parse — are preserved
 * unchanged. The availability block is ADDITIVE: the merged channel + Slice 3A
 * verdict ({@code visible}/{@code orderable}/{@code inStock}/{@code reasonCodes}),
 * an availability-aware {@code variants} projection, and an availability-aware
 * {@code optionAvailability} projection (group/value selectability). List and
 * detail agree on the product-level verdict because both run through
 * {@code ChannelCatalogService}.
 */
public record ProductDetailsResponse(UUID productId,String productCode,UUID categoryId,String categoryName,String name,String description,String imageUrl,BigDecimal price,String currencyCode,boolean available,int preparationMinutes,List<CustomizationGroupResponse> customizationGroups,
        boolean visible, boolean orderable, boolean inStock, String manualState, List<String> reasonCodes,
        List<CatalogVariantResponse> variants, List<CatalogOptionGroupResponse> optionAvailability) {}
