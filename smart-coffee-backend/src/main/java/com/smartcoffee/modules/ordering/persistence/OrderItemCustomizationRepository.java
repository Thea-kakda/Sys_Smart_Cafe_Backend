package com.smartcoffee.modules.ordering.persistence;

import com.smartcoffee.modules.ordering.domain.OrderItemCustomization;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Single batch read for the second stage of the order detail fetch: one query
 * returns the customizations of every order line in the page, in a deterministic
 * order (line, creation time, id), so no per-item query is issued.
 */
public interface OrderItemCustomizationRepository extends Repository<OrderItemCustomization, UUID> {

    @Query("""
            select c from OrderItemCustomization c
            where c.orderItem.id in :itemIds
            order by c.orderItem.id, c.createdAt, c.id
            """)
    List<OrderItemCustomization> findAllForItems(@Param("itemIds") Collection<UUID> itemIds);
}
