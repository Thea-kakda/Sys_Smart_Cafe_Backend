package com.smartcoffee.modules.promotion.persistence;

import com.smartcoffee.modules.promotion.domain.Promotion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Native access to the three promotion assignment join tables (products,
 * categories, stores) plus target-existence probes. These join tables have no
 * JPA entity (composite PK only), mirroring the role_permissions approach from
 * the RBAC administration work. Batch reads use {@code = any(cast(:ids as
 * uuid[]))} so an empty id set is a valid query, never an {@code IN ()}.
 */
public interface PromotionAssignmentRepository extends Repository<Promotion, UUID> {

    interface PromotionLinkRow {
        UUID getPromotionId();

        UUID getLinkId();
    }

    // ------------------------------------------------------------- reads

    @Query(value = "select promotion_id as promotionId, product_id as linkId "
            + "from promotion.promotion_products where promotion_id = :promotionId", nativeQuery = true)
    List<PromotionLinkRow> productsFor(@Param("promotionId") UUID promotionId);

    @Query(value = "select promotion_id as promotionId, category_id as linkId "
            + "from promotion.promotion_categories where promotion_id = :promotionId", nativeQuery = true)
    List<PromotionLinkRow> categoriesFor(@Param("promotionId") UUID promotionId);

    @Query(value = "select promotion_id as promotionId, store_id as linkId "
            + "from promotion.promotion_stores where promotion_id = :promotionId", nativeQuery = true)
    List<PromotionLinkRow> storesFor(@Param("promotionId") UUID promotionId);

    @Query(value = "select promotion_id as promotionId, product_id as linkId "
            + "from promotion.promotion_products where promotion_id = any(cast(:ids as uuid[]))", nativeQuery = true)
    List<PromotionLinkRow> productsForAll(@Param("ids") UUID[] ids);

    @Query(value = "select promotion_id as promotionId, category_id as linkId "
            + "from promotion.promotion_categories where promotion_id = any(cast(:ids as uuid[]))", nativeQuery = true)
    List<PromotionLinkRow> categoriesForAll(@Param("ids") UUID[] ids);

    @Query(value = "select promotion_id as promotionId, store_id as linkId "
            + "from promotion.promotion_stores where promotion_id = any(cast(:ids as uuid[]))", nativeQuery = true)
    List<PromotionLinkRow> storesForAll(@Param("ids") UUID[] ids);

    // ------------------------------------------- target existence probes

    @Query(value = "select product_id from catalog.products where product_id = any(cast(:ids as uuid[]))",
            nativeQuery = true)
    List<UUID> existingProductIds(@Param("ids") UUID[] ids);

    @Query(value = "select category_id from catalog.product_categories where category_id = any(cast(:ids as uuid[]))",
            nativeQuery = true)
    List<UUID> existingCategoryIds(@Param("ids") UUID[] ids);

    interface ProductCategoryRow {
        UUID getProductId();

        UUID getCategoryId();
    }

    /**
     * Category lookup for legacy validate payloads that name products but not
     * categories, so category targeting is honoured there too instead of being
     * silently ignored.
     */
    @Query(value = "select product_id as productId, category_id as categoryId from catalog.products "
            + "where product_id = any(cast(:ids as uuid[]))", nativeQuery = true)
    List<ProductCategoryRow> categoriesForProducts(@Param("ids") UUID[] ids);

    @Query(value = "select store_id from organization.stores where store_id = any(cast(:ids as uuid[]))",
            nativeQuery = true)
    List<UUID> existingStoreIds(@Param("ids") UUID[] ids);

    // ----------------------------------------------------- replacement

    @Modifying
    @Query(value = "delete from promotion.promotion_products where promotion_id = :promotionId", nativeQuery = true)
    void deleteProducts(@Param("promotionId") UUID promotionId);

    @Modifying
    @Query(value = "insert into promotion.promotion_products (promotion_id, product_id) "
            + "values (:promotionId, :linkId) on conflict do nothing", nativeQuery = true)
    void insertProduct(@Param("promotionId") UUID promotionId, @Param("linkId") UUID linkId);

    @Modifying
    @Query(value = "delete from promotion.promotion_categories where promotion_id = :promotionId", nativeQuery = true)
    void deleteCategories(@Param("promotionId") UUID promotionId);

    @Modifying
    @Query(value = "insert into promotion.promotion_categories (promotion_id, category_id) "
            + "values (:promotionId, :linkId) on conflict do nothing", nativeQuery = true)
    void insertCategory(@Param("promotionId") UUID promotionId, @Param("linkId") UUID linkId);

    @Modifying
    @Query(value = "delete from promotion.promotion_stores where promotion_id = :promotionId", nativeQuery = true)
    void deleteStores(@Param("promotionId") UUID promotionId);

    @Modifying
    @Query(value = "insert into promotion.promotion_stores (promotion_id, store_id) "
            + "values (:promotionId, :linkId) on conflict do nothing", nativeQuery = true)
    void insertStore(@Param("promotionId") UUID promotionId, @Param("linkId") UUID linkId);
}
