package com.smartcoffee.modules.cart.application;
import java.math.BigDecimal;
import java.util.*;
public interface CartCatalogGateway {
 ProductOffer product(UUID storeId,UUID productId);
 // Slice 4B.1: authoritative variant resolution. When a variant is supplied it
 // must exist, belong to the product, be active and not soft-deleted; an
 // invalid variant raises a stable error rather than falling back silently.
 // Returns null only when no variant was requested (product-level line).
 VariantOffer variant(UUID storeId,UUID productId,UUID variantId);
 List<GroupRule> groupRules(UUID productId);
 // Phase B1: categories for the cart's products, so promotion category targeting
 // is evaluated server-side against the catalog instead of a client-supplied id.
 Map<UUID,UUID> productCategoryIds(Collection<UUID> productIds);
 // stationCode and taxRate are authoritative catalog/store-product values
 // (Checkpoint 3C); null stationCode means "not configured" and is handled
 // explicitly by production routing, never by inventing a station here.
 record ProductOffer(UUID productId,String productCode,String productName,BigDecimal unitPrice,String currency,boolean available,String stationCode,BigDecimal taxRate){}
 // price is the authoritative variant base price; it REPLACES the product/store
 // base, it is never added to it (Slice 4B.1 pricing precedence).
 record VariantOffer(UUID variantId,String variantCode,String variantName,BigDecimal price,String currency){}
 record GroupRule(UUID groupId,String groupName,String selectionType,int minimum,Integer maximum,List<OptionRule> options){}
 record OptionRule(UUID optionId,String optionName,BigDecimal additionalPrice,boolean active){}
}

