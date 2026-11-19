# Reconciliation Anomaly Reference (Slice 2E)

**Scope:** the 18 detectors run by `InventoryReconciliationService`, the finding each
writes, its default severity, whether it can be repaired automatically, and what it
usually means. Referenced by the Reservation Recovery and Administrative Release
runbooks.

Conventions, restated because they govern every row below:

- The **ACTIVE reservation state is the source of truth** for what is held.
  `inventory_balances.reserved_stock` is a *derived projection*; the ledger
  (`stock_transactions`) is *append-only history*.
- A reconciliation run writes **only report rows** (`inventory_reconciliation_runs`,
  `inventory_reconciliation_findings`). A **dry run performs the whole scan and
  nothing else** — no balance, reservation or ledger write.
- A **repair never "sets the balance to the calculated sum."** A repairable finding is
  acted on only through the guarded finalization primitives (`releaseOrder` /
  `consumeOrder`), each of which re-locks the row, re-validates live state, and writes
  exactly one terminal ledger row. A finding whose live subject no longer matches is
  `SUPERSEDED`, never applied.
- Findings are opened at the detector's **default severity**; `RepairPolicy` decides
  auto-repair separately from severity. "Auto-repairable" below means *a candidate
  automatic action exists* — it is still gated at apply time, so a repair can end as
  `SUPERSEDED` or be demoted to `MANUAL_REVIEW`.

## Severity levels

| Severity | Meaning | Operator posture |
|---|---|---|
| `INFO` | informational, no action expected | none |
| `WARNING` | cosmetic/projection drift or a worker backlog; no money/stock is lost | review in batch; often self-heals once the worker runs |
| `ERROR` | a real inconsistency between held stock and its explanation | investigate; some are auto-repairable |
| `CRITICAL` | risk of lost/oversold/untraceable stock or a broken audit chain | page an operator; **never** auto-repaired without manual decision |

## The detectors

| `anomaly_type` | Default severity | Auto-repair? | What it means / likely cause |
|---|---|---|---|
| `RESERVED_BALANCE_MISMATCH` | ERROR | No | Recorded `reserved_stock` ≠ `SUM(ACTIVE reservation lines)` for a store+item. A missed release/consume or a partial write. Manual: reconcile through the primitive for the specific reservation, not by editing the balance. |
| `ACTIVE_RESERVATION_WITHOUT_BALANCE` | ERROR | No | An ACTIVE hold references a store+item with **no balance row**. Missing balance master or a cross-store line. Investigate before touching. |
| `BALANCE_WITHOUT_ACTIVE_RESERVATION` | WARNING | No | A balance reserves quantity that no ACTIVE reservation explains. Usually a release that decremented the reservation but not the projection. Re-derive after confirming no hold exists. |
| `ACTIVE_CART_RESERVATION_ON_CHECKED_OUT_CART` | WARNING | No | A checked-out cart still shows an ACTIVE cart-owned hold — conversion left the cart row ACTIVE. Expected to be rare; the checkout transaction converts atomically. |
| `CONVERTED_RESERVATION_WITHOUT_TARGET` | CRITICAL | No | A `CONVERTED` cart hold names no surviving target order reservation. Broken conversion audit chain — **never** auto-repaired. |
| `CONVERTED_QUANTITY_MISMATCH` | ERROR | No | A converted cart hold's quantity ≠ the target order reservation's share. Demand drift between cart and order. Manual. |
| `ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER` | ERROR | **Yes** (`RELEASE_CANCELLED_ORDER`) | An ACTIVE hold on a `CANCELLED` order — the cancel release was missed (crash window). Auto-repair releases the hold once via `releaseOrder(reason=DATA_RECOVERY)`; safe and idempotent. |
| `ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER` | CRITICAL | **Yes, gated** (`CONSUME_PAID_ORDER`) | An ACTIVE hold on a paid/confirmed order — settlement committed but consumption never ran (the §9 paid-but-unconsumed case). Repair attempts `PaidOrderRecoveryService.recover`, which consumes **only** if all nine §9 conditions hold; otherwise it degrades to manual review. It never releases a paid order. |
| `CONSUMED_RESERVATION_WITHOUT_CONSUMPTION_LEDGER` | CRITICAL | No | A `CONSUMED` reservation with no `SALE_CONSUMPTION` row. Ledger/history gap. Manual. |
| `DUPLICATE_CONSUMPTION_LEDGER` | CRITICAL | No | More than one `SALE_CONSUMPTION` names the same reservation. Should be impossible since V046 `uk_stock_consumption_reservation`; if seen, stop and investigate. |
| `RELEASED_RESERVATION_WITHOUT_RELEASE_LEDGER` | WARNING | No | A `RELEASED` reservation with no `SALE_RELEASE` explaining the give-back. History gap. |
| `RELEASE_AND_CONSUMPTION_CONFLICT` | CRITICAL | No | Both `SALE_RELEASE` **and** `SALE_CONSUMPTION` name the same reservation — the core invariant (XOR) is violated. Manual, urgent. |
| `ORPHAN_RESERVATION` | ERROR | No | A reservation whose owner (order or cart) no longer resolves. Global-only detector. Manual cleanup after confirming the owner is genuinely gone. |
| `ORPHAN_RESERVATION_LINE` | ERROR | No | A reservation line whose parent reservation no longer resolves. Global-only detector. |
| `ORPHAN_LEDGER_REFERENCE` | WARNING | No | A ledger transaction naming a reservation that does not exist. Audit pointer to a pruned row. |
| `EXPIRED_ACTIVE_RESERVATION` | WARNING | No | An ACTIVE reservation already past `expires_at` — the expiry worker has not run (or is backlogged). Self-heals once the worker processes it; no repair needed. |
| `NEGATIVE_BALANCE_STATE` | CRITICAL | No | `current_stock` or `reserved_stock` below zero (the DB CHECK should forbid this). Investigate immediately. |
| `RESERVATION_STORE_MISMATCH` | CRITICAL | No | A reservation's store disagrees with its order/cart/line store. Data-integrity breach. Manual, urgent. |

## Which findings require manual review (what NOT to repair manually)

Everything except `ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER` and a
provably-settled `ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER` is manual. In particular,
**do not** hand-edit `reserved_stock` to "make the numbers match" for a
`*_BALANCE_*_MISMATCH`, `..._WITHOUT_..._LEDGER`, `ORPHAN_*`, `DUPLICATE_*` or any
`CRITICAL` finding. Those are symptoms of an upstream bug; the correct fix is a real,
ledgered business action through the primitive, or a code fix — not a projection
overwrite. A balance edited by hand will silently disagree with the reservation rows
again on the next scan.

## Evidence collection without exposing customer or payment data

- The reservation **detail** endpoint (`GET /api/v1/management/inventory/reservations/{id}`)
  and the **findings** page return operational fields only — reservation id, store,
  source type, status, order/cart id, snapshot quantities, related ledger ids and open
  anomaly types. They carry **no** customer name, email, phone or payment instrument.
- To hand off an anomaly, share the `runId`, `findingId`, `reservationId`, `orderId`
  and `anomaly_type`. Those are enough for an engineer to reproduce against the DB and
  reveal no personal data.
- Do not screenshot or copy raw `identity.users`, `customer.customer_profiles` or
  `payment.payments` rows into a ticket. If a personal-data join is genuinely needed to
  diagnose, it is done by a privileged engineer server-side, never pasted into the
  reconciliation trail.
