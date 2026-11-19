package com.smartcoffee.modules.catalog.persistence;
import com.smartcoffee.modules.catalog.domain.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface StoreProductRepository extends JpaRepository<StoreProduct,StoreProductId> {
 @Query(value="""
 select p.product_id as productId,p.product_code as productCode,p.category_id as categoryId,c.category_name as categoryName,
 p.product_name as productName,p.description as description,p.image_object_key as imageObjectKey,
 coalesce(sp.override_price,p.base_price) as price,p.currency_code as currencyCode,
 (sp.available and (sp.sold_out_until is null or sp.sold_out_until < current_timestamp)) as available,
 p.preparation_minutes as preparationMinutes,p.customizable as customizable
 from catalog.store_products sp join catalog.products p on p.product_id=sp.product_id
 join catalog.product_categories c on c.category_id=p.category_id
 where sp.store_id=:storeId and p.active=true and c.active=true
 and (:categoryId is null or p.category_id=:categoryId)
 and (:availableOnly=false or (sp.available=true and (sp.sold_out_until is null or sp.sold_out_until<current_timestamp)))
 and (:search='' or lower(p.product_name) like lower(concat('%',:search,'%')) or lower(p.product_code) like lower(concat('%',:search,'%')))
 order by p.product_name asc
 """, countQuery="""
 select count(*) from catalog.store_products sp join catalog.products p on p.product_id=sp.product_id
 join catalog.product_categories c on c.category_id=p.category_id
 where sp.store_id=:storeId and p.active=true and c.active=true
 and (:categoryId is null or p.category_id=:categoryId)
 and (:availableOnly=false or (sp.available=true and (sp.sold_out_until is null or sp.sold_out_until<current_timestamp)))
 and (:search='' or lower(p.product_name) like lower(concat('%',:search,'%')) or lower(p.product_code) like lower(concat('%',:search,'%')))
 """, nativeQuery=true)
 Page<CatalogProductProjection> catalog(@Param("storeId")UUID storeId,@Param("search")String search,@Param("categoryId")UUID categoryId,@Param("availableOnly")boolean availableOnly,Pageable pageable);
 Optional<StoreProduct> findById(StoreProductId id);

 // Channel catalog read (Slice 3B, spec §3/§13/§16). ONE bounded, visibility-filtered,
 // channel-display-ordered page query. Membership excludes products not visible on the
 // requested channel BEFORE pagination (no post-filter short pages). Legacy inheritance
 // (spec §8): with no explicit channel row a legacy channel (POS/CUSTOMER_WEB/CUSTOMER_APP)
 // resolves visible/orderable from :defaultVisible/:defaultOrderable (true), while a
 // deferred channel (QR_MENU/KIOSK/DELIVERY) resolves hidden (false). Effective
 // availability stays the SAME store-product rule as the public catalog; the channel's
 // orderable gate is projected for the service to merge with the Slice 3A availability
 // verdict — availability is NOT recomputed here.
 @Query(value="""
 select p.product_id as productId,p.product_code as productCode,p.category_id as categoryId,c.category_name as categoryName,
 p.product_name as productName,p.description as description,p.image_object_key as imageObjectKey,
 coalesce(sp.override_price,p.base_price) as price,p.currency_code as currencyCode,
 (sp.available and (sp.sold_out_until is null or sp.sold_out_until < current_timestamp)) as effectiveAvailable,
 p.preparation_minutes as preparationMinutes,p.customizable as customizable,
 pc.product_channel_id as channelId,
 coalesce(pc.visible,:defaultVisible) as channelVisible,
 coalesce(pc.orderable,:defaultOrderable) as channelOrderable,
 pc.display_name as channelDisplayName,
 pc.display_description as channelDisplayDescription,
 coalesce(pc.display_order,0) as channelDisplayOrder
 from catalog.store_products sp join catalog.products p on p.product_id=sp.product_id
 join catalog.product_categories c on c.category_id=p.category_id
 left join catalog.product_channels pc on pc.store_id=sp.store_id and pc.product_id=p.product_id and pc.channel=:channel
 where sp.store_id=:storeId and p.active=true and c.active=true
 and (:categoryId is null or p.category_id=:categoryId)
 and (:availableOnly=false or (sp.available=true and (sp.sold_out_until is null or sp.sold_out_until<current_timestamp)))
 and (:search='' or lower(p.product_name) like lower(concat('%',:search,'%')) or lower(p.product_code) like lower(concat('%',:search,'%')))
 and coalesce(pc.visible,:defaultVisible)=true
 order by coalesce(pc.display_order,0) asc,p.product_name asc
 """, countQuery="""
 select count(*) from catalog.store_products sp join catalog.products p on p.product_id=sp.product_id
 join catalog.product_categories c on c.category_id=p.category_id
 left join catalog.product_channels pc on pc.store_id=sp.store_id and pc.product_id=p.product_id and pc.channel=:channel
 where sp.store_id=:storeId and p.active=true and c.active=true
 and (:categoryId is null or p.category_id=:categoryId)
 and (:availableOnly=false or (sp.available=true and (sp.sold_out_until is null or sp.sold_out_until<current_timestamp)))
 and (:search='' or lower(p.product_name) like lower(concat('%',:search,'%')) or lower(p.product_code) like lower(concat('%',:search,'%')))
 and coalesce(pc.visible,:defaultVisible)=true
 """, nativeQuery=true)
 Page<ChannelCatalogProjection> channelCatalog(@Param("storeId")UUID storeId,@Param("channel")String channel,
   @Param("defaultVisible")boolean defaultVisible,@Param("defaultOrderable")boolean defaultOrderable,
   @Param("search")String search,@Param("categoryId")UUID categoryId,@Param("availableOnly")boolean availableOnly,
   Pageable pageable);

 // Protected branch-product management read (Checkpoint 3A). Single joined
 // query (no N+1), server-side pagination, and the same verified availability
 // rule as the public catalog: available AND no active sold-out window.
 // Management view also includes inactive products/categories, override state,
 // station, versions and timestamps. Sort fields are whitelisted server-side
 // before reaching Pageable. stationCode '' means "not configured".
 @Query(value="""
  select p.product_id as productId,p.product_code as productCode,p.product_name as productName,
  p.category_id as categoryId,c.category_name as categoryName,
  p.base_price as basePrice,sp.override_price as overridePrice,
  coalesce(sp.override_price,p.base_price) as effectivePrice,
  p.currency_code as currencyCode,p.active as productActive,sp.available as branchAvailable,
  (sp.available and (sp.sold_out_until is null or sp.sold_out_until < current_timestamp)) as effectiveAvailable,
  sp.sold_out_until as soldOutUntil,sp.station_code as stationCode,
  p.preparation_minutes as preparationMinutes,p.customizable as customizable,
  p.version as productVersion,sp.version as storeProductVersion,sp.updated_at as updatedAt
  from catalog.store_products sp join catalog.products p on p.product_id=sp.product_id
  join catalog.product_categories c on c.category_id=p.category_id
  where sp.store_id=:storeId
  and (:search='' or lower(p.product_name) like lower(concat('%',:search,'%')) or lower(p.product_code) like lower(concat('%',:search,'%')))
  and (:categoryId is null or p.category_id=:categoryId)
  and (:productActive is null or p.active=:productActive)
  and (:branchAvailable is null or sp.available=:branchAvailable)
  and (:overrideState is null or (:overrideState='INHERITED' and sp.override_price is null) or (:overrideState='OVERRIDE' and sp.override_price is not null))
  and (:availability is null or (:availability='AVAILABLE' and sp.available and (sp.sold_out_until is null or sp.sold_out_until<current_timestamp)) or (:availability='UNAVAILABLE' and not (sp.available and (sp.sold_out_until is null or sp.sold_out_until<current_timestamp))))
  and (:stationCode is null or sp.station_code=:stationCode or (:stationCode='' and sp.station_code is null))
  """, countQuery="""
  select count(*) from catalog.store_products sp join catalog.products p on p.product_id=sp.product_id
  join catalog.product_categories c on c.category_id=p.category_id
  where sp.store_id=:storeId
  and (:search='' or lower(p.product_name) like lower(concat('%',:search,'%')) or lower(p.product_code) like lower(concat('%',:search,'%')))
  and (:categoryId is null or p.category_id=:categoryId)
  and (:productActive is null or p.active=:productActive)
  and (:branchAvailable is null or sp.available=:branchAvailable)
  and (:overrideState is null or (:overrideState='INHERITED' and sp.override_price is null) or (:overrideState='OVERRIDE' and sp.override_price is not null))
  and (:availability is null or (:availability='AVAILABLE' and sp.available and (sp.sold_out_until is null or sp.sold_out_until<current_timestamp)) or (:availability='UNAVAILABLE' and not (sp.available and (sp.sold_out_until is null or sp.sold_out_until<current_timestamp))))
  and (:stationCode is null or sp.station_code=:stationCode or (:stationCode='' and sp.station_code is null))
  """, nativeQuery=true)
 Page<ManagementStoreProductProjection> management(@Param("storeId")UUID storeId,@Param("search")String search,
   @Param("categoryId")UUID categoryId,@Param("productActive")Boolean productActive,
   @Param("branchAvailable")Boolean branchAvailable,@Param("overrideState")String overrideState,
   @Param("availability")String availability,@Param("stationCode")String stationCode,Pageable pageable);

 @Query(value="""
  select p.product_id as productId,p.product_code as productCode,p.product_name as productName,
  p.category_id as categoryId,c.category_name as categoryName,
  p.base_price as basePrice,sp.override_price as overridePrice,
  coalesce(sp.override_price,p.base_price) as effectivePrice,
  p.currency_code as currencyCode,p.active as productActive,sp.available as branchAvailable,
  (sp.available and (sp.sold_out_until is null or sp.sold_out_until < current_timestamp)) as effectiveAvailable,
  sp.sold_out_until as soldOutUntil,sp.station_code as stationCode,
  p.preparation_minutes as preparationMinutes,p.customizable as customizable,
  p.version as productVersion,sp.version as storeProductVersion,sp.updated_at as updatedAt
  from catalog.store_products sp join catalog.products p on p.product_id=sp.product_id
  join catalog.product_categories c on c.category_id=p.category_id
  where sp.store_id=:storeId and p.product_id=:productId
  """, nativeQuery=true)
 Optional<ManagementStoreProductProjection> managementOne(@Param("storeId")UUID storeId,@Param("productId")UUID productId);
}

