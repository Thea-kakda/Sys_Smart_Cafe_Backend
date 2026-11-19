package com.smartcoffee.modules.payment.persistence;

import com.smartcoffee.modules.payment.domain.Receipt;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {
    Optional<Receipt> findByPaymentId(UUID paymentId);

    /** Order-keyed read for the receipt GET contract (one row per settled payment). */
    List<Receipt> findByOrderId(UUID orderId);

    /** Serializes concurrent issuers on the receipt row (guarded status claim). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Receipt r where r.id=:id")
    Optional<Receipt> lock(@Param("id") UUID id);
}
