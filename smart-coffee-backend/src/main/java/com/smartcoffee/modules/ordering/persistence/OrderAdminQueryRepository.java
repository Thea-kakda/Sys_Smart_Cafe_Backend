package com.smartcoffee.modules.ordering.persistence;

import com.smartcoffee.modules.ordering.domain.Order;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read model for Orders Administration search (Master Prompt §8). Single-table
 * native query over {@code ordering.orders}; no customer personal data is selected.
 *
 * <p>Filter parameters follow the sentinel/flag convention used by
 * {@code StaffQueryRepository} to avoid Hibernate 7 / pgjdbc null-parameter type
 * inference: text params are never null (empty means "off"), {@code customerText}
 * always carries a valid UUID literal gated by {@code customerFilter}, and
 * {@code storeIds} is never empty (a sentinel UUID is supplied when
 * {@code storeScopeAll} short-circuits the predicate). The caller (service) always
 * supplies a concrete store scope or an explicit all-scope flag, so no unbounded
 * cross-tenant scan is possible.
 */
public interface OrderAdminQueryRepository extends Repository<Order, UUID> {

    @Query(value = """
            select o.order_id as orderId, o.order_number as orderNumber, o.store_id as storeId,
                   o.customer_id as customerId, o.source as source, o.fulfillment_type as fulfillmentType,
                   o.status as status, o.payment_status as paymentStatus, o.currency_code as currencyCode,
                   o.subtotal as subtotal, o.discount_amount as discountAmount, o.tax_amount as taxAmount,
                   o.delivery_fee as deliveryFee, o.rounding_amount as roundingAmount, o.total_amount as totalAmount,
                   o.total_quantity as totalQuantity, o.ordered_at as orderedAt, o.updated_at as updatedAt,
                   o.version as version
            from ordering.orders o
            where (:storeScopeAll = true or o.store_id in (:storeIds))
              and (:numberFilter = false or lower(o.order_number) like lower(concat('%', :numberText, '%')))
              and (:customerFilter = false or o.customer_id = cast(:customerText as uuid))
              and (:statusFilter = false or o.status = :statusValue)
              and (:paymentFilter = false or o.payment_status = :paymentValue)
              and (:sourceFilter = false or o.source = :sourceValue)
              and (:fulfillmentFilter = false or o.fulfillment_type = :fulfillmentValue)
              and o.ordered_at >= :fromTs and o.ordered_at <= :toTs
            """, countQuery = """
            select count(*) from ordering.orders o
            where (:storeScopeAll = true or o.store_id in (:storeIds))
              and (:numberFilter = false or lower(o.order_number) like lower(concat('%', :numberText, '%')))
              and (:customerFilter = false or o.customer_id = cast(:customerText as uuid))
              and (:statusFilter = false or o.status = :statusValue)
              and (:paymentFilter = false or o.payment_status = :paymentValue)
              and (:sourceFilter = false or o.source = :sourceValue)
              and (:fulfillmentFilter = false or o.fulfillment_type = :fulfillmentValue)
              and o.ordered_at >= :fromTs and o.ordered_at <= :toTs
            """, nativeQuery = true)
    Page<OrderSummaryProjection> search(@Param("storeScopeAll") boolean storeScopeAll,
            @Param("storeIds") List<UUID> storeIds,
            @Param("numberFilter") boolean numberFilter, @Param("numberText") String numberText,
            @Param("customerFilter") boolean customerFilter, @Param("customerText") String customerText,
            @Param("statusFilter") boolean statusFilter, @Param("statusValue") String statusValue,
            @Param("paymentFilter") boolean paymentFilter, @Param("paymentValue") String paymentValue,
            @Param("sourceFilter") boolean sourceFilter, @Param("sourceValue") String sourceValue,
            @Param("fulfillmentFilter") boolean fulfillmentFilter, @Param("fulfillmentValue") String fulfillmentValue,
            @Param("fromTs") Instant fromTs, @Param("toTs") Instant toTs, Pageable pageable);

    /** Read-only projection over one order header; mirrors {@link com.smartcoffee.modules.ordering.api.ManagementOrderSummary}. */
    interface OrderSummaryProjection {
        UUID getOrderId();

        String getOrderNumber();

        UUID getStoreId();

        UUID getCustomerId();

        String getSource();

        String getFulfillmentType();

        String getStatus();

        String getPaymentStatus();

        String getCurrencyCode();

        java.math.BigDecimal getSubtotal();

        java.math.BigDecimal getDiscountAmount();

        java.math.BigDecimal getTaxAmount();

        java.math.BigDecimal getDeliveryFee();

        java.math.BigDecimal getRoundingAmount();

        java.math.BigDecimal getTotalAmount();

        int getTotalQuantity();

        Instant getOrderedAt();

        Instant getUpdatedAt();

        long getVersion();
    }
}
