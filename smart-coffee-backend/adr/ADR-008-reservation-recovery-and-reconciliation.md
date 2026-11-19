# ADR-008: Reservation Recovery, Reconciliation Ownership and Multi-Node Expiration

- **Status:** Accepted
- **Decision date:** 2026-10-27
- **Owners:** Architecture, Backend, Inventory
- **Scope:** Expired cart/order reservation cleanup, paid-but-unconsumed recovery, reservation-to-balance reconciliation, controlled repair, safe administrative release, multi-node worker claiming
- **Related:** extends ADR-007 (the single consumption point and one-terminal-transition rule); does not supersede it

## Context

ADR-007 established that `SALE_CONSUMPTION` happens exactly once, at payment settlement, and that a reservation leaves `ACTIVE` only once. Slice 2D made checkout ownership move as metadata. That left the operational edges of the reservation lifecycle unguarded:

1. **Nothing reclaimed abandoned holds on a schedule.** An expired cart that was never checked out, or an unpaid order whose payment window lapsed, kept `reserved_stock` pinned forever. A naive `UPDATE ... WHERE expires_at < now()` sweeper is unsafe the moment more than one instance runs: two nodes pick the same rows, block each other on the row lock, and race to release.
2. **A crash between settlement and consumption could strand a paid order.** Payment is committed and the money is real, but if the process dies before `consumeOrder` the hold stays `ACTIVE`. Releasing it would give back stock that was legitimately sold; consuming it blindly would double-consume if settlement had, in fact, completed.
3. **There was no way to prove the ledger and the balances agree.** `reserved_stock` is a running projection; the reservation rows are the source of the held amount. Nothing periodically compared the two or surfaced an orphan hold, a cancelled order with an `ACTIVE` hold, or a consumed reservation with no consumption row.
4. **Operators had no safe lever.** A stuck hold needed either direct `UPDATE`s against production tables or nothing at all.

The invariant this slice must not break: **the active reservation state is the source of truth for what is held; `reserved_stock` is a derived projection; the ledger is append-only history.** No cleanup or repair path may treat a balance number as authoritative or "recompute" stock into existence.

## Decision

### One release/consume primitive, many entry points

Every terminal move of a reservation goes through the SAME guarded primitives of `InventoryReservationFinalizationService` that ADR-007 introduced — `releaseCart`, `releaseOrder`, `consumeOrder` (plus the new `releaseReservation`, the only id-addressed release). Recovery, reconciliation repair and administrative release are *new callers of old primitives*, not new writers. Each primitive locks the row `PESSIMISTIC_WRITE`, only leaves `ACTIVE` once, moves `reserved_stock` by the exact persisted snapshot, leaves `current_stock` untouched on a release, and writes exactly one terminal ledger row. A replay on a terminal reservation returns `false` (a silent no-op), so `SALE_RELEASE` and `SALE_CONSUMPTION` can never both land on one reservation.

### Paid-but-unconsumed recovery reuses consumption, gated by nine conditions

`PaidOrderRecoveryService.recover(orderId, actor)` re-derives the settlement verdict from live state and consumes **only** when every §9 gate holds — payment provably `SETTLED`, order status in the consumable set, reservation still `ACTIVE`, same store, no prior `SALE_CONSUMPTION`, no prior `SALE_RELEASE`, and held ≤ reserved/current. Anything short of "provably settled and provably unconsumed" returns `MANUAL_REVIEW`; it never releases a paid order and never consumes an ambiguous one. The expiry worker calls this same recovery when it meets a `SETTLED` order — recovery and expiry are one decision tree, not two subsystems.

### Multi-node expiration claims the reservation row itself

The ORDER expiry worker claims candidates with a bounded `SELECT ... FOR UPDATE SKIP LOCKED` and an atomic `UPDATE` that stamps `processing_owner/_token/_started_at/_attempts` on the reservation row (V047) — the proven convention already used by the outbox claim repository, deliberately cloned rather than inventing a job table. Two nodes therefore never receive the same reservation; `staleBefore` reclaims a claim whose owner died mid-batch; `maxAttempts` is a dead-letter ceiling against hot-looping a persistently failing row. Each claimed id is then processed in its OWN transaction (bounded retry gives a fresh transaction per attempt on a recognized transient failure). The CART expiry sweep keeps its existing per-cart `FOR UPDATE` processor and simply runs the release through `releaseCart`. Scheduling stays disabled under the test profile, so workers are invoked directly — the claim logic is identical whether a scheduler or a test triggers it.

### Reconciliation is a derived read that reports; repair acts through the primitive

`InventoryReconciliationService` runs a bounded, paginated set of FULL-OUTER-JOIN detectors that compare recorded `reserved_stock` against the derived `SUM` of `ACTIVE` reservation lines (and analogous join-based checks for orphan/cancelled/paid/consumed/released states). It writes only `inventory_reconciliation_runs` and `inventory_reconciliation_findings` — report rows, never operational state. **A dry run is the whole scan and nothing more**: no balance, reservation or ledger write. `RepairPolicy` classifies an anomaly as auto-repairable only when there is a single obviously correct idempotent action (release a cancelled order's hold; consume a provably settled order); everything else is `MANUAL_ONLY`. Even a repairable finding is revalidated live under a lock before acting, so a stale report row is `SUPERSEDED`, never applied, and a repair records a reason on the finding (`repaired_by` is nullable on purpose: a system batch repair has no user actor).

### Administrative release is permission- and scope-gated and idempotent

`AdminReservationReleaseService.release` requires `reservation:release` plus the store scope (global only for SUPER_ADMIN/ADMIN), accepts an `expectedVersion` optimistic token and a mandatory reason, and claims an idempotency key (`RESERVATION_RELEASE`, SHA-256 canonical request hash) so a retried call replays the original result instead of double-releasing. Only an `ACTIVE` hold releases; a `CONSUMED` or `SETTLED` hold is refused with a stable conflict code, a `CONVERTED` cart hold is refused (its target owns the stock), and an already-terminal hold is a stable `released=false` no-op. The endpoint layer is thin — the service re-enforces permission and scope regardless of what the controller checked.

### Metrics and health stay bounded and low-cardinality

A collector exposes a few gauges (expired-pending, open-error, reconciliation backlog) and a health indicator (`UP` / `DEGRADED` on pending anomalies) using fixed labels only; no per-store or per-reservation high-cardinality series is emitted. Response bodies expose operational fields only — never customer identity or payment data.

## Consequences

- A held quantity can no longer leak: expired carts and unpaid orders are reclaimed by a job that is safe to run on every node simultaneously.
- A crash after settlement is recoverable without risk of double-consume, because recovery is the same guarded primitive and the DB backstop `uk_stock_consumption_reservation` (V046) still forbids a second consumption.
- Operators get a dry-run-first, evidence-based workflow and a single, auditable release lever; the balance-vs-derived report makes silent drift visible.
- Reconciliation never "fixes" a number into agreement — a repair is always a real, ledgered business action, so the ledger and the projection stay consistent by construction rather than by adjustment.
- The two report tables and the `processing_*` claim columns are additive and idempotent; no released migration was edited.

## Alternatives rejected

- **A separate scheduled-job/claim table for expiry.** Adds a second source of "who is working on what" that can itself drift from the reservation. Claiming on the reservation row keeps one owner and one lock target, and mirrors the already-proven outbox pattern.
- **Auto-repair by writing `reserved_stock` to the derived sum.** Fast and tempting, but it treats the projection as truth, produces a balance change with no ledger event, and silently hides the underlying bug (orphan, double-hold, missed release). Every repair here must be a guarded, ledgered primitive call or nothing.
- **Releasing a paid-but-unconsumed order to "be safe."** That gives back stock that was actually sold; the correct conservative action for an ambiguous settlement is manual review, never an automatic release.
- **A `DELETE`-style sweeper that releases everything matching `expires_at < now()` in one statement.** Unsafe under concurrency (lock contention and double release) and it bypasses the per-reservation revalidation that keeps `SALE_RELEASE` XOR `SALE_CONSUMPTION` true.
- **Letting the admin endpoint trust the controller's permission check.** Defense-in-depth: the application service re-checks capability and store scope, because the ownership rule is that only Inventory decides how much is held and when it moves — the HTTP layer is not the guard.
