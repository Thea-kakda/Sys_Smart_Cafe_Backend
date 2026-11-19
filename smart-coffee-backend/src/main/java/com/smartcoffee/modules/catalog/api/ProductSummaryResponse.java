package com.smartcoffee.modules.catalog.api;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
/**
 * Store catalog summary row (Slice 3B). The original fields are unchanged for
 * backward compatibility; the availability block is purely ADDITIVE so existing
 * POS/Customer clients that ignore unknown JSON keep working. {@code available}
 * stays the legacy effective store-product boolean; the new {@code orderable}/
 * {@code inStock}/{@code reasonCodes} carry the merged channel + availability
 * verdict. {@code manualState} is the operator control (AUTO / MANUAL_SOLD_OUT).
 */
public record ProductSummaryResponse(UUID productId,String productCode,UUID categoryId,String categoryName,String name,String description,String imageUrl,BigDecimal price,String currencyCode,boolean available,int preparationMinutes,boolean customizable,
        boolean visible, boolean orderable, boolean inStock, String manualState, List<String> reasonCodes) {}
