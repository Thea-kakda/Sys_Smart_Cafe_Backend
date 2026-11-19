package com.smartcoffee.modules.payment.persistence;

import com.smartcoffee.modules.payment.domain.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id=:id")
    Optional<Payment> lock(@Param("id") UUID id);

    Optional<Payment> findByProviderReference(String ref);

    List<Payment> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    boolean existsByOrderIdAndStatus(UUID orderId, PaymentStatus status);

    @Query("select p.id from Payment p where p.status in ('PENDING','PROCESSING') and p.expiresAt<=:now order by p.expiresAt")
    List<UUID> expired(@Param("now") Instant now, Pageable page);
}

