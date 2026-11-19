package com.smartcoffee.modules.organization.persistence;

import com.smartcoffee.modules.organization.domain.Store;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreRepository extends JpaRepository<Store, UUID> {
    Optional<Store> findByIdAndActiveTrue(UUID id);

    Page<Store> findByActiveTrueAndNameContainingIgnoreCase(String search, Pageable pageable);

    boolean existsByCode(String code);

    Optional<Store> findByCode(String code);

    List<Store> findByActiveTrueOrderByCreatedAtAsc();

    /**
     * Management search: includes inactive branches and filters by free-text
     * (name or code) and optional active state. Used only behind branch:read.
     *
     * <p>Native query on purpose: Hibernate 7 + pgjdbc binary transfer cannot
     * infer null-typed JPQL parameters here (Postgres plans unknown parameters
     * as bytea and fails with "function lower(bytea) does not exist"). Same
     * sentinel style as the catalog management search: {@code search} is never
     * null ('' means no text filter) and {@code activeFilter} is a primitive
     * boolean flag paired with {@code activeValue}.
     */
    @Query(value = """
            select s.store_id as storeId, s.store_code as storeCode, s.store_name as storeName,
            s.address as address, s.phone as phone, s.timezone as timezone,
            s.default_currency as defaultCurrency, s.default_language as defaultLanguage,
            s.active as active, s.created_at as createdAt, s.updated_at as updatedAt,
            s.version as version
            from organization.stores s
            where (:search='' or lower(s.store_name) like lower(concat('%', :search, '%'))
            or lower(s.store_code) like lower(concat('%', :search, '%')))
            and (:activeFilter = false or s.active = :activeValue)
            """, countQuery = """
            select count(*) from organization.stores s
            where (:search='' or lower(s.store_name) like lower(concat('%', :search, '%'))
            or lower(s.store_code) like lower(concat('%', :search, '%')))
            and (:activeFilter = false or s.active = :activeValue)
            """, nativeQuery = true)
    Page<StoreSearchProjection> search(@Param("search") String search,
            @Param("activeFilter") boolean activeFilter, @Param("activeValue") boolean activeValue,
            Pageable pageable);

    /** Read-only projection over organization.stores for the management list. */
    interface StoreSearchProjection {
        UUID getStoreId();

        String getStoreCode();

        String getStoreName();

        String getAddress();

        String getPhone();

        String getTimezone();

        String getDefaultCurrency();

        String getDefaultLanguage();

        boolean isActive();

        Instant getCreatedAt();

        Instant getUpdatedAt();

        long getVersion();
    }
}
