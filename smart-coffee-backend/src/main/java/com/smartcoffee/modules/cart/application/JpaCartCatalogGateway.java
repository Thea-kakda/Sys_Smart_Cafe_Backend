package com.smartcoffee.modules.cart.application;
import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.catalog.application.EffectiveCustomizationResolver;
import com.smartcoffee.modules.catalog.domain.StoreProductId;
import com.smartcoffee.modules.catalog.persistence.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class JpaCartCatalogGateway implements CartCatalogGateway {
 private final ProductRepository products; private final StoreProductRepository storeProducts; private final ProductVariantRepository variants; private final EffectiveCustomizationResolver customization;
 public JpaCartCatalogGateway(ProductRepository p,StoreProductRepository s,ProductVariantRepository v,EffectiveCustomizationResolver customization){products=p;storeProducts=s;variants=v;this.customization=customization;}
 @Transactional(readOnly=true) public ProductOffer product(UUID storeId,UUID productId){var p=products.findByIdAndActiveTrue(productId).orElseThrow(()->new ResourceNotFoundException("Product not found"));var sp=storeProducts.findById(new StoreProductId(storeId,productId)).orElseThrow(()->new ResourceNotFoundException("Product is not offered by this store"));var price=sp.overridePrice()==null?p.basePrice():sp.overridePrice();return new ProductOffer(p.id(),p.code(),p.name(),price,p.currencyCode(),sp.available(),sp.stationCode(),p.taxRate());}
 // Slice 4B.1: a supplied variant is authoritative — it must exist, belong to
 // the requested product, not be soft-deleted and be active, with a valid
 // non-negative price inside the product's scale convention. Every rejection
 // carries a stable code; there is deliberately NO fallback to the product base
 // price, because silently repricing a rejected variant mischarges the customer.
 // Store/branch restrictions stay enforced by product(): a variant is only
 // reachable through a product the store actually offers.
 @Transactional(readOnly=true) public VariantOffer variant(UUID storeId,UUID productId,UUID variantId){
  if(variantId==null) return null;
  var v=variants.findByIdAndDeletedAtIsNull(variantId).orElseThrow(()->new ValidationException("VARIANT_NOT_FOUND","Variant not found"));
  if(!Objects.equals(v.productId(),productId)) throw new ValidationException("VARIANT_PRODUCT_MISMATCH","Variant does not belong to the selected product");
  if(!v.active()) throw new ConflictException("VARIANT_INACTIVE","Variant is no longer available");
  var price=v.price();
  if(price==null||price.signum()<0||price.scale()>4) throw new ValidationException("INVALID_VARIANT_PRICE","Variant price is invalid");
  return new VariantOffer(v.id(),v.code(),v.name(),price,v.currencyCode());
 }
 // Round 2: resolves through the shared effective-customization resolver so cart
 // validation honours per-product selection limits, the allowed-option subset and
 // price overrides — the same values the Dashboard writes and POS/Customer reads.
 @Transactional(readOnly=true) public List<GroupRule> groupRules(UUID productId){return customization.resolve(productId).stream().map(g->new GroupRule(g.groupId(),g.name(),g.selectionType(),g.minimumSelections(),g.maximumSelections(),g.options().stream().map(o->new OptionRule(o.optionId(),o.name(),o.effectivePrice(),o.masterActive())).toList())).toList();}
 @Transactional(readOnly=true) public Map<UUID,UUID> productCategoryIds(Collection<UUID> productIds){
  if(productIds==null||productIds.isEmpty()) return Map.of();
  Map<UUID,UUID> categories=new HashMap<>();
  for(var p:products.findAllById(productIds)) categories.put(p.id(),p.categoryId());
  return categories;
 }
}
