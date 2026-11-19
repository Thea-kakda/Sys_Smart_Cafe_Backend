package com.smartcoffee.modules.production.persistence;

import com.smartcoffee.modules.production.domain.ProductionOrder;
import com.smartcoffee.modules.production.domain.ProductionOrderStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JPA repository for the {@link ProductionOrder} aggregate.
 *
 * <p>Child collections ({@code materials}, {@code outputs}, {@code waste})
 * are intentionally NOT declared on a {@code @EntityGraph}: Hibernate's
 * {@code MultipleBagFetchException} forbids fetching more than one
 * {@code List}-mapped association on the same query, and the aggregate keeps
 * them as ordered Lists so a shop-floor printout stays deterministic.
 * Every read path (lock, list, get) runs inside a service-layer
 * {@code @Transactional} boundary, so the mapper's iteration triggers a lazy
 * load and completes within the same session. With ≤ a handful of children
 * per order the extra fetch is not a hot spot; the concurrency ITs (Phase
 * 5A.3) revisit this if the batch planning path ever widens.
 *
 * <p>{@link #lock(UUID)} acquires a {@code SELECT ... FOR UPDATE} row lock
 * inside the surrounding {@code @Transactional} service method. Combined with
 * the JPA {@code @Version} column this gives us belt-and-braces concurrency:
 * the DB serialises any two commands that race for the same order, and the
 * optimistic version check catches stale echoes from a caller whose lock
 * arrived after another's transaction committed.
 */
public interface ProductionOrderRepository extends JpaRepository<ProductionOrder, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ProductionOrder o where o.id = :id")
    Optional<ProductionOrder> lock(@Param("id") UUID id);

    @Query("select o from ProductionOrder o where o.id = :id")
    Optional<ProductionOrder> findWithChildrenById(@Param("id") UUID id);

    Page<ProductionOrder> findByStoreIdAndStatusIn(UUID storeId, List<ProductionOrderStatus> statuses,
            Pageable pageable);

    Page<ProductionOrder> findByStoreId(UUID storeId, Pageable pageable);

    boolean existsByNumber(String number);
}
