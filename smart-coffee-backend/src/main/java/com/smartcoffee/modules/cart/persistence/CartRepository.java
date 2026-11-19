package com.smartcoffee.modules.cart.persistence;
import com.smartcoffee.modules.cart.domain.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface CartRepository extends JpaRepository<Cart,UUID>{
 @EntityGraph(attributePaths={"items","items.customizations"}) Optional<Cart> findFirstByCustomerIdAndStoreIdAndStatusOrderByCreatedAtDesc(UUID customerId,UUID storeId,CartStatus status);
 @EntityGraph(attributePaths={"items","items.customizations"}) Optional<Cart> findByIdAndCustomerId(UUID id,UUID customerId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @EntityGraph(attributePaths={"items","items.customizations"}) @Query("select c from Cart c where c.id=:id and c.customerId=:customerId") Optional<Cart> lockOwned(@Param("id")UUID id,@Param("customerId")UUID customerId);
 /** Cart-row claim for the expiry worker (no collection fetch: the worker only
  *  needs the status/expiry and the reservation release, never the item graph). */
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from Cart c where c.id=:id") Optional<Cart> lockById(@Param("id")UUID id);
 @Query("select c.id from Cart c where c.status='ACTIVE' and c.expiresAt<=:now order by c.expiresAt") List<UUID> expiredIds(@Param("now")Instant now,Pageable page);
}

