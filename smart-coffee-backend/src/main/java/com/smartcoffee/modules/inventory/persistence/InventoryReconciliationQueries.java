package com.smartcoffee.modules.inventory.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Set-based reconciliation queries (Slice 2E, spec §8/§20). Every method is a
 * single aggregate/join query bounded by store and by {@code maxItems}; none of
 * them loops per reservation, so a store scan is O(#queries), not O(#rows).
 *
 * <p>The derived reserved stock is authoritative only as a <i>comparison</i>: it
 * SUMs the persisted ACTIVE reservation lines grouped by store+item (never from
 * ledger rows), per spec §8. The FULL OUTER JOIN then surfaces all three imbalance
 * shapes — balance-only, reservation-only, and quantity mismatch — in one pass.
 *
 * <p>These are read-only. Nothing here mutates stock.
 */
@Repository
public class InventoryReconciliationQueries {

    private final JdbcTemplate jdbc;

    public InventoryReconciliationQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Derived reserved stock: SUM(ACTIVE reservation lines) per store+item (§8). */
    public List<DerivedReservedStock> derivedReservedStock(UUID storeId) {
        return jdbc.query(
                "select r.store_id, l.ingredient_id, sum(l.quantity_reserved) derived "
                        + "from inventory.inventory_reservations r "
                        + "join inventory.inventory_reservation_lines l on l.reservation_id = r.reservation_id "
                        + "where r.status = 'ACTIVE' and r.store_id = ? "
                        + "group by r.store_id, l.ingredient_id order by l.ingredient_id",
                (rs, i) -> new DerivedReservedStock(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBigDecimal(3)),
                storeId);
    }

    /**
     * Balance recorded reserved_stock vs derived ACTIVE reservation sum, all three
     * imbalance shapes in one FULL OUTER JOIN. Populates expected=derived,
     * actual=recorded (either side may be null when a row is missing on that side).
     */
    public List<ReconciliationRow> balanceVsDerived(UUID storeId, int maxItems) {
        return jdbc.query(
                """
                with derived as (
                    select r.store_id sid, l.ingredient_id iid, sum(l.quantity_reserved) dq
                    from inventory.inventory_reservations r
                    join inventory.inventory_reservation_lines l on l.reservation_id = r.reservation_id
                    where r.status = 'ACTIVE' and r.store_id = ?
                    group by r.store_id, l.ingredient_id
                )
                select coalesce(b.store_id, d.sid) store_id,
                       coalesce(b.ingredient_id, d.iid) ingredient_id,
                       null reservation_id,
                       null order_id,
                       null cart_id,
                       d.dq expected,
                       b.reserved_stock actual
                from inventory.inventory_balances b
                full outer join derived d on d.sid = b.store_id and d.iid = b.ingredient_id
                where (b.store_id = ? or d.sid = ?)
                  and coalesce(b.reserved_stock, 0) <> coalesce(d.dq, 0)
                order by store_id, ingredient_id
                limit ?
                """,
                rowMapper(), storeId, storeId, storeId, maxItems);
    }

    /** current/reserved below zero, or reserved above current (DB CHECK should forbid). */
    public List<ReconciliationRow> negativeBalance(UUID storeId, int maxItems) {
        return jdbc.query(
                "select store_id, ingredient_id, null reservation_id, null order_id, null cart_id, "
                        + "current_stock expected, reserved_stock actual from inventory.inventory_balances "
                        + "where store_id = ? and (current_stock < 0 or reserved_stock < 0 or reserved_stock > current_stock) "
                        + "order by ingredient_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** ACTIVE reservations already past expiry (worker lag). */
    public List<ReconciliationRow> expiredActive(UUID storeId, Instant now, int maxItems) {
        return jdbc.query(
                "select store_id, null ingredient_id, reservation_id, order_id, cart_id, null expected, null actual "
                        + "from inventory.inventory_reservations "
                        + "where store_id = ? and status = 'ACTIVE' and expires_at <= ? order by expires_at limit ?",
                rowMapper(), storeId, java.sql.Timestamp.from(now), maxItems);
    }

    /** ACTIVE cart-owned hold on a cart already CHECKED_OUT. */
    public List<ReconciliationRow> activeCartOnCheckedOutCart(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, null order_id, r.cart_id, null expected, null actual "
                        + "from inventory.inventory_reservations r join cart.carts c on c.cart_id = r.cart_id "
                        + "where r.store_id = ? and r.status = 'ACTIVE' and r.source_type = 'CART_ITEM' and c.status = 'CHECKED_OUT' "
                        + "order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** ACTIVE order hold on a CANCELLED order. */
    public List<ReconciliationRow> activeOrderOnCancelledOrder(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, r.order_id, r.cart_id, null expected, null actual "
                        + "from inventory.inventory_reservations r join ordering.orders o on o.order_id = r.order_id "
                        + "where r.store_id = ? and r.status = 'ACTIVE' and r.source_type = 'ORDER' and o.status = 'CANCELLED' "
                        + "order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /**
     * ACTIVE order hold whose order has a PAID payment but no SALE_CONSUMPTION for
     * the reservation — the paid-but-unconsumed crash window (§9 recovery candidate).
     */
    public List<ReconciliationRow> activeOrderOnPaidOrder(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, r.order_id, r.cart_id, null expected, null actual "
                        + "from inventory.inventory_reservations r "
                        + "join ordering.orders o on o.order_id = r.order_id "
                        + "join payment.payments p on p.order_id = r.order_id and p.status = 'PAID' "
                        + "where r.store_id = ? and r.status = 'ACTIVE' and r.source_type = 'ORDER' "
                        + "and not exists (select 1 from inventory.stock_transactions t "
                        + "  where t.reservation_id = r.reservation_id and t.transaction_type = 'SALE_CONSUMPTION') "
                        + "order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** CONVERTED cart hold whose target order reservation does not exist (§7/§10). */
    public List<ReconciliationRow> convertedWithoutTarget(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, r.converted_order_id order_id, r.cart_id, "
                        + "null expected, null actual from inventory.inventory_reservations r "
                        + "where r.store_id = ? and r.status = 'CONVERTED' "
                        + "and (r.converted_to_reservation_id is null or not exists (select 1 "
                        + "  from inventory.inventory_reservations t where t.reservation_id = r.converted_to_reservation_id)) "
                        + "order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** CONSUMED reservation with no matching SALE_CONSUMPTION ledger row. */
    public List<ReconciliationRow> consumedWithoutLedger(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, r.order_id, r.cart_id, null expected, null actual "
                        + "from inventory.inventory_reservations r "
                        + "where r.store_id = ? and r.status = 'CONSUMED' and not exists (select 1 "
                        + "  from inventory.stock_transactions t where t.reservation_id = r.reservation_id "
                        + "  and t.transaction_type = 'SALE_CONSUMPTION') order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** More than one SALE_CONSUMPTION names the same reservation. */
    public List<ReconciliationRow> duplicateConsumption(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, r.order_id, r.cart_id, null expected, "
                        + "count(t.stock_transaction_id)::numeric actual "
                        + "from inventory.inventory_reservations r join inventory.stock_transactions t "
                        + "  on t.reservation_id = r.reservation_id and t.transaction_type = 'SALE_CONSUMPTION' "
                        + "where r.store_id = ? group by r.store_id, r.reservation_id, r.order_id, r.cart_id "
                        + "having count(t.stock_transaction_id) > 1 order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** RELEASED reservation with no SALE_RELEASE explaining the give-back. */
    public List<ReconciliationRow> releasedWithoutLedger(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, r.order_id, r.cart_id, null expected, null actual "
                        + "from inventory.inventory_reservations r "
                        + "where r.store_id = ? and r.status = 'RELEASED' and not exists (select 1 "
                        + "  from inventory.stock_transactions t where t.reservation_id = r.reservation_id "
                        + "  and t.transaction_type = 'SALE_RELEASE') order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** Both a SALE_RELEASE and a SALE_CONSUMPTION name the same reservation (§24 winner rule). */
    public List<ReconciliationRow> releaseAndConsumptionConflict(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, r.order_id, r.cart_id, null expected, null actual "
                        + "from inventory.inventory_reservations r "
                        + "where r.store_id = ? and exists (select 1 from inventory.stock_transactions t "
                        + "  where t.reservation_id = r.reservation_id and t.transaction_type = 'SALE_RELEASE') "
                        + "and exists (select 1 from inventory.stock_transactions t "
                        + "  where t.reservation_id = r.reservation_id and t.transaction_type = 'SALE_CONSUMPTION') "
                        + "order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** A reservation whose owner (order or cart) no longer resolves. */
    public List<ReconciliationRow> orphanReservation(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, r.order_id, r.cart_id, null expected, null actual "
                        + "from inventory.inventory_reservations r "
                        + "where r.store_id = ? and ((r.source_type = 'ORDER' and r.order_id is not null "
                        + "  and not exists (select 1 from ordering.orders o where o.order_id = r.order_id)) "
                        + " or (r.source_type = 'CART_ITEM' and r.cart_id is not null "
                        + "  and not exists (select 1 from cart.carts c where c.cart_id = r.cart_id))) "
                        + "order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** A ledger transaction naming a reservation that does not exist. */
    public List<ReconciliationRow> orphanLedgerReference(UUID storeId, int maxItems) {
        return jdbc.query(
                "select t.store_id, null ingredient_id, t.reservation_id, null order_id, t.cart_id, null expected, null actual "
                        + "from inventory.stock_transactions t "
                        + "where t.store_id = ? and t.reservation_id is not null and not exists (select 1 "
                        + "  from inventory.inventory_reservations r where r.reservation_id = t.reservation_id) "
                        + "order by t.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /** A reservation whose store disagrees with its order's or cart's store. */
    public List<ReconciliationRow> storeMismatch(UUID storeId, int maxItems) {
        return jdbc.query(
                "select r.store_id, null ingredient_id, r.reservation_id, r.order_id, r.cart_id, null expected, null actual "
                        + "from inventory.inventory_reservations r "
                        + "left join ordering.orders o on o.order_id = r.order_id "
                        + "left join cart.carts c on c.cart_id = r.cart_id "
                        + "where r.store_id = ? and ((o.order_id is not null and o.store_id <> r.store_id) "
                        + "  or (c.cart_id is not null and c.store_id <> r.store_id)) "
                        + "order by r.reservation_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /**
     * A CONVERTED cart hold whose ingredient quantity is not carried by the target
     * order reservation (spec §7). expected=the cart hold's line quantity,
     * actual=the matching target-reservation line quantity (null when absent).
     */
    public List<ReconciliationRow> convertedQuantityMismatch(UUID storeId, int maxItems) {
        return jdbc.query(
                "select cr.store_id, cl.ingredient_id, cr.reservation_id, cr.converted_order_id order_id, cr.cart_id, "
                        + "cl.quantity_reserved expected, tl.quantity_reserved actual "
                        + "from inventory.inventory_reservations cr "
                        + "join inventory.inventory_reservation_lines cl on cl.reservation_id = cr.reservation_id "
                        + "join inventory.inventory_reservations tr on tr.reservation_id = cr.converted_to_reservation_id "
                        + "left join inventory.inventory_reservation_lines tl "
                        + "  on tl.reservation_id = tr.reservation_id and tl.ingredient_id = cl.ingredient_id "
                        + "where cr.store_id = ? and cr.status = 'CONVERTED' "
                        + "and coalesce(tl.quantity_reserved, 0) <> coalesce(cl.quantity_reserved, 0) "
                        + "order by cr.reservation_id, cl.ingredient_id limit ?",
                rowMapper(), storeId, maxItems);
    }

    /**
     * A reservation line whose parent reservation no longer resolves (FK should
     * prevent this; detected defensively). Store cannot be attributed (parent gone),
     * so {@code storeId} is null on these rows.
     */
    public List<ReconciliationRow> orphanReservationLine(int maxItems) {
        return jdbc.query(
                "select null store_id, l.ingredient_id, l.reservation_id, null order_id, null cart_id, "
                        + "l.quantity_reserved expected, null actual "
                        + "from inventory.inventory_reservation_lines l "
                        + "where not exists (select 1 from inventory.inventory_reservations r "
                        + "  where r.reservation_id = l.reservation_id) "
                        + "order by l.reservation_id, l.ingredient_id limit ?",
                rowMapper(), maxItems);
    }

    // ------------------------------------------------------------- internals

    /** Total reservations for one store (any status) — the reconciliation scan size. */
    public long countReservations(UUID storeId) {
        Long n = jdbc.queryForObject("select count(*) from inventory.inventory_reservations where store_id = ?",
                Long.class, storeId);
        return n == null ? 0 : n;
    }

    /** Global ACTIVE hold counts per source type, for the low-cardinality gauges (§17). */
    public long countActiveBySource(String sourceType) {
        Long n = jdbc.queryForObject(
                "select count(*) from inventory.inventory_reservations where status = 'ACTIVE' and source_type = ?",
                Long.class, sourceType);
        return n == null ? 0 : n;
    }

    /** Global ACTIVE holds already past expiry — the expiry-worker backlog gauge. */
    public long countExpiredPending(Instant now) {
        Long n = jdbc.queryForObject(
                "select count(*) from inventory.inventory_reservations where status = 'ACTIVE' and expires_at <= ?",
                Long.class, java.sql.Timestamp.from(now));
        return n == null ? 0 : n;
    }

    /**
     * Active store ids for a bounded global reconciliation scan (§13: cross-store is
     * admin-only). Read natively so inventory keeps no Java dependency on the
     * organization module — reconciliation is inherently a cross-schema reader.
     */
    public List<UUID> listActiveStoreIds() {
        return jdbc.query("select store_id from organization.stores where active = true order by store_id",
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    /**
     * Related ledger transaction ids for one reservation, newest first (spec §13
     * reservation-detail "Related ledger transaction IDs"). Names only the ids; the
     * management response never echoes quantities beyond the reservation snapshot.
     */
    public List<UUID> ledgerTransactionIds(UUID reservationId) {
        return jdbc.query("select stock_transaction_id from inventory.stock_transactions "
                + "where reservation_id = ? order by created_at desc", (rs, i) -> rs.getObject(1, UUID.class),
                reservationId);
    }

    /**
     * Order state needed by the paid-but-unconsumed gate (§9). Read via native SQL
     * so the inventory module does not take a Java dependency on the ordering
     * module (reconciliation is inherently a cross-schema reader). Null row → the
     * order does not resolve (an orphan); the caller treats that as ambiguous.
     */
    public OrderSnapshot orderSnapshot(UUID orderId) {
        List<OrderSnapshot> rows = jdbc.query(
                "select store_id, status from ordering.orders where order_id = ?",
                (rs, i) -> new OrderSnapshot(rs.getObject(1, UUID.class), rs.getString(2)),
                orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Settlement verdict for an order (idempotent across payment retries): a PAID
     * row is committed settlement; a lingering PROCESSING/PENDING with no PAID is
     * ambiguous; anything else is not settled. Backed by the V007 unique index that
     * allows at most one PAID payment per order.
     */
    public PaymentSettlement paymentSettlement(UUID orderId) {
        Boolean paid = jdbc.queryForObject(
                "select exists(select 1 from payment.payments where order_id = ? and status = 'PAID')",
                Boolean.class, orderId);
        if (Boolean.TRUE.equals(paid)) {
            return PaymentSettlement.SETTLED;
        }
        Boolean inflight = jdbc.queryForObject(
                "select exists(select 1 from payment.payments where order_id = ? and status in ('PROCESSING','PENDING'))",
                Boolean.class, orderId);
        return Boolean.TRUE.equals(inflight) ? PaymentSettlement.AMBIGUOUS : PaymentSettlement.NOT_SETTLED;
    }

    private static RowMapper<ReconciliationRow> rowMapper() {
        return (rs, i) -> new ReconciliationRow(uuid(rs, "store_id"), uuid(rs, "ingredient_id"),
                uuid(rs, "reservation_id"), uuid(rs, "order_id"), uuid(rs, "cart_id"),
                rs.getBigDecimal("expected"), rs.getBigDecimal("actual"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : UUID.fromString(value.toString());
    }

    /** Derived reserved stock for one store+item (§8 aggregate row). */
    public record DerivedReservedStock(UUID storeId, UUID itemId, BigDecimal derivedReserved) {
    }

    /** Order store + status snapshot for the recovery gate (native read, no module cycle). */
    public record OrderSnapshot(UUID storeId, String status) {
    }

    /** Settlement verdict of an order for the paid-but-unconsumed gate. */
    public enum PaymentSettlement {
        SETTLED, AMBIGUOUS, NOT_SETTLED
    }
}
