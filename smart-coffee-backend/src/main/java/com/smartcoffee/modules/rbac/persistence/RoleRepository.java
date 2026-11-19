package com.smartcoffee.modules.rbac.persistence;

import com.smartcoffee.modules.rbac.domain.Role;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Pessimistic-write lookup used by the last-SUPER_ADMIN guard: serializes
     * concurrent revoke/disable decisions on the role row before the holder
     * count is re-checked inside the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.code = :code")
    Optional<Role> lockByCode(@Param("code") String code);
}

