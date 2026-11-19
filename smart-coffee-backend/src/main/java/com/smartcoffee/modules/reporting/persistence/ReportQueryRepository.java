package com.smartcoffee.modules.reporting.persistence;

import com.smartcoffee.modules.ordering.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Database-side report aggregation over ordering/order_items (Master Prompt
 * §10.4 — orders are never loaded into memory). Sentinel/flag parameter style
 * established by the staff and order admin read models: text parameters are
 * never null ('' when off), boolean flags short-circuit optional predicates,
 * and {@code :storeIds} is always non-empty (NIL sentinel when scopeAll).
 *
 * Currency is part of GROUP BY in every money query: mixed-currency sums are
 * structurally impossible here. Managed type is Order because ordering is the
 * reporting source of truth; no entity is mutated through this repository.
 */
public interface ReportQueryRepository extends Repository<Order, UUID> {

    interface SalesRowProjection {
        String getPeriod();

        String getCurrencyCode();

        long getOrderCount();

        long getPaidOrderCount();

        BigDecimal getGrossSales();

        BigDecimal getDiscounts();

        BigDecimal getTax();

        BigDecimal getDeliveryFees();

        BigDecimal getRounding();

        BigDecimal getNetSales();
    }

    interface ConsumptionRowProjection {
        String getProductName();

        String getIngredientName();

        String getUnitCode();

        BigDecimal getUnitsConsumed();
    }

    /**
     * Sales summary grouped by (period, currency). Period buckets are cut in the
     * supplied IANA timezone via {@code AT TIME ZONE}; the grouping unit is
     * whitelisted by the caller (DAY/WEEK/MONTH) and cast explicitly so
     * date_trunc/to_char receive typed parameters. Amount aggregates use only
     * the final PAID payment state; orderCount keeps every order in the window
     * so unpaid/cancelled volume stays visible without touching revenue.
     */
    @Query(value = """
            select case cast(:grouping as text)
                     when 'DAY' then to_char(date_trunc('day', o.ordered_at at time zone cast(:tz as text)), 'YYYY-MM-DD')
                     when 'WEEK' then to_char(date_trunc('week', o.ordered_at at time zone cast(:tz as text)), 'IYYY-"W"IW')
                     else to_char(date_trunc('month', o.ordered_at at time zone cast(:tz as text)), 'YYYY-MM')
                   end as period,
                   o.currency_code as currencyCode,
                   count(*) as orderCount,
                   count(*) filter (where o.payment_status = 'PAID') as paidOrderCount,
                   sum(case when o.payment_status = 'PAID' then o.subtotal else 0 end) as grossSales,
                   sum(case when o.payment_status = 'PAID' then o.discount_amount else 0 end) as discounts,
                   sum(case when o.payment_status = 'PAID' then o.tax_amount else 0 end) as tax,
                   sum(case when o.payment_status = 'PAID' then o.delivery_fee else 0 end) as deliveryFees,
                   sum(case when o.payment_status = 'PAID' then o.rounding_amount else 0 end) as rounding,
                   sum(case when o.payment_status = 'PAID' then o.total_amount else 0 end) as netSales
            from ordering.orders o
            where (:scopeAll = true or o.store_id in (:storeIds))
              and (:currencyFilter = false or o.currency_code = cast(:currencyText as text))
              and o.ordered_at >= :fromTs and o.ordered_at <= :toTs
            group by 1, 2
            order by 1, 2
            limit 1000
            """, nativeQuery = true)
    List<SalesRowProjection> salesSummary(@Param("scopeAll") boolean scopeAll,
            @Param("storeIds") List<UUID> storeIds, @Param("currencyFilter") boolean currencyFilter,
            @Param("currencyText") String currencyText, @Param("fromTs") Instant from, @Param("toTs") Instant to,
            @Param("grouping") String grouping, @Param("tz") String timezone);

    /**
     * Ingredient consumption reconstructed from the recipe version each order
     * item actually used (ordering.order_items.recipe_version_id snapshot) —
     * quantity × recipe line requirement × (1 + wastage). Quantities only: no
     * cost column in this query because historical cost is not reconstructable
     * (see BACKEND API GAP in the report envelope).
     */
    @Query(value = """
            select oi.product_name_snapshot as productName,
                   i.ingredient_name as ingredientName,
                   u.unit_code as unitCode,
                   sum(cast(oi.quantity as numeric) * rvl.quantity_required_base * (1 + rvl.wastage_percent / 100.0))
                       as unitsConsumed
            from ordering.orders o
            join ordering.order_items oi on oi.order_id = o.order_id
            join recipe.recipe_version_lines rvl on rvl.recipe_version_id = oi.recipe_version_id
            join inventory.ingredients i on i.ingredient_id = rvl.ingredient_id
            join inventory.units_of_measure u on u.unit_id = i.base_unit_id
            where (:scopeAll = true or o.store_id in (:storeIds))
              and o.payment_status = 'PAID'
              and o.ordered_at >= :fromTs and o.ordered_at <= :toTs
            group by 1, 2, 3
            order by 1, 2
            limit 1000
            """, nativeQuery = true)
    List<ConsumptionRowProjection> inventoryConsumption(@Param("scopeAll") boolean scopeAll,
            @Param("storeIds") List<UUID> storeIds, @Param("fromTs") Instant from, @Param("toTs") Instant to);

    /** Paid items lacking a recipe-version snapshot; surfaced for transparency. */
    @Query(value = """
            select count(*)
            from ordering.orders o
            join ordering.order_items oi on oi.order_id = o.order_id
            where (:scopeAll = true or o.store_id in (:storeIds))
              and o.payment_status = 'PAID'
              and o.ordered_at >= :fromTs and o.ordered_at <= :toTs
              and oi.recipe_version_id is null
            """, nativeQuery = true)
    long countItemsWithoutRecipe(@Param("scopeAll") boolean scopeAll, @Param("storeIds") List<UUID> storeIds,
            @Param("fromTs") Instant from, @Param("toTs") Instant to);

    @Query(value = "select timezone from organization.stores where store_id = cast(:storeId as uuid)",
            nativeQuery = true)
    String storeTimezone(@Param("storeId") UUID storeId);
}
