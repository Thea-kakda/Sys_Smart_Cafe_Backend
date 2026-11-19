package com.smartcoffee.modules.production.domain;

/**
 * Lifecycle of a planned manufacturing order (Slice 5A). Distinct from the
 * {@link ProductionStatus} of a sale-order kitchen ticket: the two workflows
 * coexist in the {@code production} schema but have completely different
 * state graphs and inventory semantics.
 *
 * <p>Transitions (spec §4):
 * <ul>
 *   <li>{@code DRAFT → PLANNED | CANCELLED}</li>
 *   <li>{@code PLANNED → IN_PROGRESS | CANCELLED}</li>
 *   <li>{@code IN_PROGRESS → COMPLETED | CANCELLED} (cancel only while no
 *       irreversible inventory movement has occurred — always true in Phase
 *       5A.1; enforced by {@code ProductionOrderCommandService.cancel})</li>
 *   <li>{@code COMPLETED → CLOSED}</li>
 *   <li>{@code CANCELLED, CLOSED} — terminal, immutable</li>
 * </ul>
 *
 * <p>Persistence: {@code production.production_orders.status} stores this as
 * a varchar guarded by {@code ck_production_order_status} (V051). Adding a
 * value here requires a matching CHECK update in a new migration.
 */
public enum ProductionOrderStatus {
    DRAFT,
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    CLOSED
}
