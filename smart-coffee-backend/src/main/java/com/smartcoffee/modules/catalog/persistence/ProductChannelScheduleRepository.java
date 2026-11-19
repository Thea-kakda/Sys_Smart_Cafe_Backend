package com.smartcoffee.modules.catalog.persistence;

import com.smartcoffee.modules.catalog.domain.ProductChannelSchedule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Weekly schedule persistence for {@link ProductChannel} (Slice 3B, spec §7). The
 * catalog page loads every active schedule for the page's channel ids in ONE batch
 * query ({@link #findByProductChannelIdIn}) and evaluates windows in memory against
 * the store timezone and injected clock — no per-product schedule query.
 */
public interface ProductChannelScheduleRepository extends JpaRepository<ProductChannelSchedule, UUID> {

    List<ProductChannelSchedule> findByProductChannelIdIn(List<UUID> productChannelIds);

    List<ProductChannelSchedule> findByProductChannelIdOrderByDayOfWeekAscStartLocalTimeAsc(UUID productChannelId);

    void deleteByProductChannelId(UUID productChannelId);
}
