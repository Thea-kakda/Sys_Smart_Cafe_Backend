package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "lines")
    @Query("select r from InventoryReservation r where r.id=:id")
    Optional<InventoryReservation> lock(@Param("id") UUID id);

    Optional<InventoryReservation> findByOrderId(UUID orderId);

    /**
     * The single order-owned reservation, locked for write (settlement consumes
     * it, cancellation releases it). Deliberately NOT an entity graph join: a
     * pessimistic lock over a fetched collection is not portable to PostgreSQL,
     * so the lines load lazily inside the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InventoryReservation r where r.orderId=:orderId")
    Optional<InventoryReservation> lockByOrderId(@Param("orderId") UUID orderId);

    /**
     * Every ACTIVE hold of one cart, locked in a deterministic order for the
     * checkout conversion. Same reason as above for the plain query: a
     * pessimistic lock plus a collection fetch is not portable.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InventoryReservation r where r.cartId=:cartId and r.status='ACTIVE' "
            + "order by r.cartItemId")
    List<InventoryReservation> lockActiveByCartId(@Param("cartId") UUID cartId);

    /**
     * The single reservation row a cart line owns (V045 unique index). Locked for
     * write because the delta engine mutates its held quantities.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "lines")
    @Query("select r from InventoryReservation r where r.cartItemId=:cartItemId")
    Optional<InventoryReservation> lockByCartItemId(@Param("cartItemId") UUID cartItemId);

    @Query("select r from InventoryReservation r where r.cartItemId=:cartItemId")
    Optional<InventoryReservation> findByCartItemId(@Param("cartItemId") UUID cartItemId);

    /**
     * Every ACTIVE hold of one cart (clear / cancel / expiry / checkout handoff).
     * The cart row is locked by the caller, so no row lock is taken here: a
     * pessimistic lock plus a collection join fetch is not portable to
     * PostgreSQL (DISTINCT/FOR UPDATE restrictions).
     */
    @EntityGraph(attributePaths = "lines")
    @Query("select r from InventoryReservation r where r.cartId=:cartId and r.status='ACTIVE' order by r.createdAt")
    List<InventoryReservation> findActiveByCartId(@Param("cartId") UUID cartId);

    @Query("select r.id from InventoryReservation r where r.status='ACTIVE' and r.expiresAt<=:now order by r.expiresAt")
    List<UUID> expired(@Param("now") Instant now, Pageable page);

    @Query(value = "select count(*) from inventory.inventory_reservations where cart_id = :cartId and status = 'ACTIVE'",
            nativeQuery = true)
    long countActiveByCartId(@Param("cartId") UUID cartId);
}
