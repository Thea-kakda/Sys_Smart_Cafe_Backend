package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
}
