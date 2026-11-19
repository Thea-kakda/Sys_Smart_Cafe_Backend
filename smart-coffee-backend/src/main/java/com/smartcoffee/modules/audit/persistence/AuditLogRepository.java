package com.smartcoffee.modules.audit.persistence;

import com.smartcoffee.modules.audit.domain.AuditLog;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Query("select a from AuditLog a where (:store is null or a.storeId=:store) and (:category is null or a.category=:category) and a.occurredAt between :from and :to order by a.occurredAt desc")
    Page<AuditLog> search(@Param("store") UUID store, @Param("category") String category, @Param("from") Instant from,
            @Param("to") Instant to, Pageable pageable);

    @Override
    default void delete(AuditLog entity) {
        throw new UnsupportedOperationException("Audit logs are append-only");
    }

    @Override
    default void deleteById(Long id) {
        throw new UnsupportedOperationException("Audit logs are append-only");
    }
}

