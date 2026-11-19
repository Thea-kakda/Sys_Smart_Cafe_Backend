# ADR-007: Checkout Reservation Conversion and the Settlement Consumption Point

- **Status:** Accepted
- **Decision date:** 2026-10-26
- **Owners:** Architecture, Backend, Inventory
- **Scope:** Cart-to-order inventory handoff, exactly-once `SALE_CONSUMPTION`, cancellation release
- **Supersedes:** the "release cart hold, then reserve the order" handoff of Slice 2C and the "consume when production starts" rule of Slice 2A

## Context

Slice 2C gave cart lines their own persisted reservations. Checkout then handed them to the order by releasing the cart holds (`SALE_RELEASE`, reason `CHECKOUT_HANDOFF`) and taking a **newly calculated** order reservation in the same transaction. Two problems followed:

1. **Demand was recalculated from current catalog data.** The order reservation was built by `RecipeDemandService` from the product's active recipe, not from what the cart had actually persisted. A line configured with a variant, a milk replacement, a sugar multiplier, an extra shot or a packaging variant could therefore reserve (and later consume) different quantities than the customer's cart held — the reported 27 g vs 18 g bean defect.
2. **The release/re-reserve pair was only safe because it was in one transaction.** Any future split across a transaction boundary, retry or async step would expose the stock as free between the two writes.

Consumption itself was triggered at production start, which is a workstation event, not a commercial one: an order could be consumed while unpaid, and could not be consumed twice only by accident of the production state machine.

## Decision

### Ownership moves as metadata, not as stock movement

Checkout converts the cart's ACTIVE holds:

- The cart rows are kept and marked `CONVERTED`, carrying `converted_at`, `converted_order_id`, `converted_order_line_id` and `converted_to_reservation_id`. They remain the audit record of what the cart held.
- ONE order reservation is created by **copying the persisted cart snapshots** (`inventory_reservation_lines`), never by recalculating. `order_id` stays unique (V005), so an order owns at most one reservation and the existing `findByOrderId` contract is unchanged.
- `reserved_stock` and `current_stock` do not move, and no `SALE_RELEASE` is written. There is no committed state in which the quantity is briefly available.
- Only a cart line that never held stock (a pre-V045 line) has its demand calculated — once, through the same `ConsumptionPlanService` the cart uses — and that part alone is reserved for real, with its own `SALE_RESERVATION` row (reason `LEGACY_CHECKOUT_RESERVE`).

### Consumption happens at payment settlement

`SALE_CONSUMPTION` is written by exactly one business event: the payment that confirms the order (`JpaPaymentOrderAdapter.confirm`), inside the settlement transaction for both cash and KHQR verification. Production start no longer touches inventory.

Consumption draws the reservation snapshot down by its own quantities (`current_stock` and `reserved_stock` both fall), visits balance rows in ascending inventory-item id order, and aborts the whole consumption on any zero-row guarded update (`RESERVATION_BALANCE_CONFLICT`).

### Release happens only before consumption

Cancellation (`ORDER_CANCELLED`) and payment-window expiry (`PAYMENT_FAILED`) release an ACTIVE reservation: only `reserved_stock` moves, exactly one `SALE_RELEASE` is written, and a replay — or a cancellation after consumption — is a no-op. Consumed stock can never be given back by cancellation.

### One terminal transition per reservation

Every terminal transition locks the reservation row (`PESSIMISTIC_WRITE` by `order_id`, or the cart's ACTIVE holds by `cart_id`) and only leaves `ACTIVE` once. V046 adds `uk_stock_consumption_reservation` as a database backstop so a second `SALE_CONSUMPTION` for the same reservation is impossible even if the application guard were bypassed.

## Consequences

- The order holds and consumes exactly what the customer configured; later recipe edits cannot change a placed order's demand.
- Checkout no longer needs a release to make the stock free, so the no-release-gap invariant holds by construction rather than by transaction luck.
- The ledger tells the truth: one `SALE_RESERVATION` per cart line, zero `SALE_RELEASE` for a converted line, one `SALE_CONSUMPTION` per settled order.
- Unpaid orders hold stock until the payment window expires; the expiry job is the only automatic release path.
- Reporting must read `SALE_CONSUMPTION` (not production events) for cost of goods sold.

## Alternatives rejected

- **One order reservation per cart item (drop the unique `order_id`).** Cleaner traceability per line, but it changes the V005 constraint, every `findByOrderId` `Optional` call site, and the consumption path; the aggregated snapshot with `converted_order_line_id` links keeps the traceability at a fraction of the risk.
- **Keep the release + re-reserve handoff and only fix the demand source.** It preserves two writes for one ownership change, keeps the no-release-gap invariant dependent on transaction boundaries, and produces a `SALE_RELEASE` that never corresponded to a real business release.
- **Consume at production start and also at settlement, guarded by status.** Two entry points for one event is exactly the double-consumption class this ADR removes.
