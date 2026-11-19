package com.smartcoffee.modules.catalog.persistence;

import com.smartcoffee.modules.catalog.domain.Channel;
import com.smartcoffee.modules.catalog.domain.ProductChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Publication-policy persistence for {@link ProductChannel} (Slice 3B, spec §18).
 * Management reads/writes go through here; the catalog read uses the joined
 * {@code channelCatalog} native query on {@link StoreProductRepository} instead, so
 * a whole page resolves in a single bounded query rather than per-product lookups.
 */
public interface ProductChannelRepository extends JpaRepository<ProductChannel, UUID> {

    Optional<ProductChannel> findByStoreIdAndProductIdAndChannel(UUID storeId, UUID productId, Channel channel);

    List<ProductChannel> findAllByStoreIdAndProductId(UUID storeId, UUID productId);

    List<ProductChannel> findAllByStoreIdAndProductIdIn(UUID storeId, List<UUID> productIds);

    boolean existsByStoreIdAndProductIdAndChannel(UUID storeId, UUID productId, Channel channel);
}
