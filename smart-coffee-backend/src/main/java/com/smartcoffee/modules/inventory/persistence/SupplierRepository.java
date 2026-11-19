package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.Supplier;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    List<Supplier> findByActiveTrueOrderByCodeAsc();

    @Query("select s from Supplier s where lower(s.code) like lower(concat('%', :q, '%')) "
            + "or lower(s.name) like lower(concat('%', :q, '%')) order by s.code")
    Page<Supplier> search(@Param("q") String query, Pageable pageable);
}
