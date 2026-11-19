package com.smartcoffee.modules.ordering.persistence;

import com.smartcoffee.modules.ordering.domain.Order;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/**
 * Order persistence. Reads fetch only the {@code items} collection in the graph;
 * customizations are loaded in one batch by {@link OrderItemCustomizationRepository}.
 * Joining both collections at once multiplies {@code order_items} rows and hydrates
 * the List-backed {@code Order.items} with one duplicate entry per customization.
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {
    @EntityGraph(attributePaths = { "items" })
    Optional<Order> findByIdAndCustomerId(UUID id, UUID customerId);

    /** Customer order history page; the graph prevents N+1 item loading per page. */
    @EntityGraph(attributePaths = { "items" })
    Page<Order> findByCustomerIdOrderByOrderedAtDesc(UUID customerId, Pageable p);

    @EntityGraph(attributePaths = { "items" })
    @Override
    Optional<Order> findById(UUID id);

    boolean existsByCartId(UUID cartId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id=:id")
    Optional<Order> lock(@Param("id") UUID id);
}
