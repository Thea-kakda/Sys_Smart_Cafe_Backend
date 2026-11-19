package com.smartcoffee.modules.identity.persistence;

import com.smartcoffee.modules.identity.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read model for the User Directory (Full-stack wiring §1.1): a paginated view
 * over ALL identity users — staff, customers, or both — for global
 * administration. Follows the established StaffQueryRepository pattern: cross-
 * aggregate reads are native SQL (identity.users ⋈ assignments ⋈ optional
 * customer.customer_profiles), every write still goes through the owning JPA
 * repositories, and filter parameters use the sentinel style (empty string = no
 * filter, boolean flags gate UUID/instant predicates) to sidestep Hibernate 7 /
 * pgjdbc null-parameter type inference.
 *
 * <p>Deliberately read-only ({@code Repository}, not JpaRepository) and never
 * selects credential columns (password hash lives in identity.user_credentials,
 * tokens in auth.auth_sessions — neither is referenced here).
 */
public interface UserDirectoryQueryRepository extends Repository<User, UUID> {

    @Query(value = """
            select u.user_id as userId, u.full_name as fullName, u.email as email, u.phone as phone,
                   u.preferred_language as preferredLanguage, u.status as status,
                   u.created_at as createdAt, u.updated_at as updatedAt, u.version as version,
                   exists (select 1 from customer.customer_profiles cp
                           where cp.user_id = u.user_id) as customer
            from identity.users u
            where (:search = '' or lower(u.full_name) like lower(concat('%', :search, '%'))
                   or lower(u.email) like lower(concat('%', :search, '%')))
              and (:statusFilter = false or u.status = :statusValue)
              and (:roleCodeFilter = false or exists (
                    select 1 from identity.user_role_assignments a1
                    join identity.roles r1 on r1.role_id = a1.role_id
                    where a1.user_id = u.user_id and a1.revoked_at is null
                          and (a1.expires_at is null or a1.expires_at > :now) and r1.role_code = :roleCode))
              and (:storeIdFilter = false or exists (
                    select 1 from identity.user_role_assignments a2
                    where a2.user_id = u.user_id and a2.store_id = cast(:storeId as uuid)
                          and a2.revoked_at is null and (a2.expires_at is null or a2.expires_at > :now)))
              and (:accountType = 'ALL'
                   or (:accountType = 'STAFF' and exists (
                        select 1 from identity.user_role_assignments a3
                        where a3.user_id = u.user_id and a3.revoked_at is null
                              and (a3.expires_at is null or a3.expires_at > :now)))
                   or (:accountType = 'CUSTOMER' and exists (
                        select 1 from customer.customer_profiles cp2
                        where cp2.user_id = u.user_id)))
            order by u.created_at desc, u.user_id desc
            """, countQuery = """
            select count(*) from identity.users u
            where (:search = '' or lower(u.full_name) like lower(concat('%', :search, '%'))
                   or lower(u.email) like lower(concat('%', :search, '%')))
              and (:statusFilter = false or u.status = :statusValue)
              and (:roleCodeFilter = false or exists (
                    select 1 from identity.user_role_assignments a1
                    join identity.roles r1 on r1.role_id = a1.role_id
                    where a1.user_id = u.user_id and a1.revoked_at is null
                          and (a1.expires_at is null or a1.expires_at > :now) and r1.role_code = :roleCode))
              and (:storeIdFilter = false or exists (
                    select 1 from identity.user_role_assignments a2
                    where a2.user_id = u.user_id and a2.store_id = cast(:storeId as uuid)
                          and a2.revoked_at is null and (a2.expires_at is null or a2.expires_at > :now)))
              and (:accountType = 'ALL'
                   or (:accountType = 'STAFF' and exists (
                        select 1 from identity.user_role_assignments a3
                        where a3.user_id = u.user_id and a3.revoked_at is null
                              and (a3.expires_at is null or a3.expires_at > :now)))
                   or (:accountType = 'CUSTOMER' and exists (
                        select 1 from customer.customer_profiles cp2
                        where cp2.user_id = u.user_id)))
            """, nativeQuery = true)
    Page<DirectoryUserProjection> search(@Param("search") String search,
            @Param("statusFilter") boolean statusFilter, @Param("statusValue") String statusValue,
            @Param("roleCodeFilter") boolean roleCodeFilter, @Param("roleCode") String roleCode,
            @Param("storeIdFilter") boolean storeIdFilter, @Param("storeId") String storeId,
            @Param("accountType") String accountType,
            @Param("now") Instant now, Pageable pageable);

    @Query(value = """
            select u.user_id as userId, u.full_name as fullName, u.email as email, u.phone as phone,
                   u.preferred_language as preferredLanguage, u.status as status,
                   u.created_at as createdAt, u.updated_at as updatedAt, u.version as version,
                   exists (select 1 from customer.customer_profiles cp
                           where cp.user_id = u.user_id) as customer
            from identity.users u where u.user_id = cast(:userId as uuid)
            """, nativeQuery = true)
    DirectoryUserProjection findUser(@Param("userId") UUID userId);

    /**
     * Customer-module profile fields for the directory detail (read-only cross-
     * module SQL, same precedent as joining organization.stores for assignment
     * labels). Null row means the user has no customer profile.
     */
    @Query(value = """
            select cp.customer_id as customerId, cp.loyalty_number as loyaltyNumber,
                   cp.preferred_store_id as preferredStoreId, s.store_name as preferredStoreName,
                   cp.birth_date as birthDate, cp.marketing_opt_in as marketingOptIn,
                   cp.created_at as customerSince
            from customer.customer_profiles cp
            left join organization.stores s on s.store_id = cp.preferred_store_id
            where cp.user_id = cast(:userId as uuid)
            """, nativeQuery = true)
    List<CustomerDirectoryView> customerView(@Param("userId") UUID userId);

    /** Read-only projection over identity.users plus the customer-profile flag. */
    interface DirectoryUserProjection {
        UUID getUserId();

        String getFullName();

        String getEmail();

        String getPhone();

        String getPreferredLanguage();

        String getStatus();

        Instant getCreatedAt();

        Instant getUpdatedAt();

        long getVersion();

        boolean isCustomer();
    }

    /** Customer-profile slice exposed on the directory detail endpoint. */
    interface CustomerDirectoryView {
        UUID getCustomerId();

        String getLoyaltyNumber();

        UUID getPreferredStoreId();

        String getPreferredStoreName();

        java.time.LocalDate getBirthDate();

        boolean isMarketingOptIn();

        Instant getCustomerSince();
    }
}
