# Reservation Recovery Runbook (Slice 2E)

**Audience:** operators / on-call backend engineers.
**Owner of the data:** the Inventory module. No path in this runbook edits a balance
or ledger row by hand; every corrective action goes through a guarded primitive that
locks the reservation, re-validates live state, and writes exactly one terminal ledger
row. Companion docs: `RECONCILIATION_ANOMALY_REFERENCE.md` (what each finding means),
`ADMIN_RESERVATION_RELEASE_RUNBOOK.md` (the single-hold release lever).

## 0. Mental model (read this first)

- **Active reservation state = source of truth for what is held.** `reserved_stock` is
  a projection; the ledger is history. Never "fix" a projection number directly.
- **Two cleanup workers run automatically and are safe on every node at once** (they
  claim via `FOR UPDATE SKIP LOCKED`, so two instances never process one reservation):
  - *Cart expiry* (`CartExpirationService`) releases an expired cart's ACTIVE holds —
    one `SALE_RELEASE`, reason `CART_EXPIRED`.
  - *Order expiry* (`OrderReservationExpiryService`) walks expired order holds. For an
    unpaid order it expires the order and releases with `ORDER_RESERVATION_EXPIRED`;
    for a **settled** order it runs **recovery** (see §5), never a release; for an
    ambiguous payment it skips and leaves the hold for manual review.
- **`SALE_RELEASE` and `SALE_CONSUMPTION` are mutually exclusive** for one reservation.
  A recovery that consumes can never also release, and vice-versa.

## 1. Run a DRY-RUN reconciliation first — always

A dry run executes the full scan and writes **only report rows**. It moves no stock,
no reservation, no ledger. Never run a repair scan without first reading a dry run.

```
POST /api/v1/management/inventory/reconciliation/run
Authorization: Bearer <admin token>
Content-Type: application/json

{ "storeId": "<store-uuid>", "dryRun": true, "maxItems": 2000 }
```

- `storeId` omitted/`null` = a **global** scan (SUPER_ADMIN/ADMIN only; a store-scoped
  manager is refused). A store-scoped operator must pass one of their own stores.
- `maxItems` is a per-detector cap, clamped server-side.
- Response `data` is a `RunResult`: `runId`, `scannedCount`, `anomalyCount`,
  `automaticallyRepairableCount`, `manuallyReviewableCount`, `repairedCount` (0 for a
  dry run), `dryRun` (true). **Keep the `runId`** — you repair against it later.

Read the findings page (also non-mutating):

```
GET /api/v1/management/inventory/reconciliation?storeId=<store-uuid>&page=0&size=20
```

## 2. Interpret severity

Each finding carries a severity (`INFO`/`WARNING`/`ERROR`/`CRITICAL`) and a status
(`OPEN`/`REPAIRABLE`/`MANUAL_REVIEW`/`REPAIRED`/`SUPERSEDED`/`DISMISSED`). See
`RECONCILIATION_ANOMALY_REFERENCE.md` for the full table. Rule of thumb:

- `WARNING` — projection drift or a worker backlog; often self-heals once the expiry
  worker runs. No money/stock is lost. Batch-review, do not rush.
- `ERROR` — a real held-vs-explained inconsistency. Some are auto-repairable (§3).
- `CRITICAL` — possible lost/oversold/untraceable stock or a broken audit chain.
  **Never auto-repaired.** Investigate before any action.

## 3. Which findings are auto-repairable

Exactly two anomaly types have a candidate automatic action (`RepairPolicy`):

1. `ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER` → release the abandoned hold once
   (reason `DATA_RECOVERY`). Safe and idempotent.
2. `ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER` → attempt §9 recovery (consume a paid but
   unconsumed order) **only if** all nine settlement/consumption conditions hold;
   otherwise it degrades to manual review.

Everything else is `MANUAL_ONLY`. "Auto-repairable" means a candidate action exists —
the repair still re-validates the live row, so a finding whose subject is already
terminal is `SUPERSEDED`, never applied.

## 4. Repair against the dry-run you just read

Two ways to repair. Prefer the operator-driven path (explicit reason, own audit):

```
# (a) operator repair of the still-valid findings from a specific scan
POST /api/v1/management/inventory/reconciliation/<runId>/repair
Idempotency-Key: <uuid>              # reused key => the replay applies nothing twice
{ "dryRun": false, "reason": "released holds left by the 03:00 checkout crash window" }
```

or a single scan-and-repair:

```
# (b) run with dryRun=false: scans, then applies the guarded auto-repair pass
POST /api/v1/management/inventory/reconciliation/run
{ "storeId": "<store-uuid>", "dryRun": false }
```

`reconciliation:repair` permission is required for both. A non-empty `reason` is
mandatory for the operator path. Replaying the same `Idempotency-Key` reports
`repairedCount = 0` and writes no second ledger row.

## 5. Paid-but-unconsumed recovery (the delicate case)

A payment that committed but whose consumption never ran (crash after settlement)
shows as `ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER` (CRITICAL). Do **not** release it —
that gives back stock that was actually sold. Recovery consumes it through the same
guarded `consumeOrder` primitive, gated by nine conditions (settled payment,
consumable order status, still-ACTIVE hold, same store, no prior consumption, no prior
release, held ≤ reserved/current). If any gate fails, the outcome is `MANUAL_REVIEW`
and the hold is left untouched for a human. The order expiry worker applies this same
recovery automatically when it meets a settled order.

## 6. Releasing an abandoned reservation safely

If a single stuck hold needs release and it is not part of a batch, use the admin
release endpoint (see `ADMIN_RESERVATION_RELEASE_RUNBOOK.md`). Only an `ACTIVE` hold
releases; a consumed/settled hold is refused, and a converted cart hold is refused
because its order target owns the stock.

## 7. Verify balances afterward

After any repair or release, confirm the invariant rather than trusting the counter:

- The reservation status is terminal (`RELEASED`/`CONSUMED`) with **exactly one**
  terminal ledger row (a `SALE_RELEASE` XOR a `SALE_CONSUMPTION`, never both).
- `current_stock` did **not** move for a release (a release returns held, not sold,
  stock); it moved down only for a consumption.
- Re-run a **dry run** for the same store. The repaired anomaly is gone and no new
  anomaly of the same kind reappeared. If a `*_BALANCE_*_MISMATCH` persists after a
  "successful" repair, stop — the underlying bug is still live.

## 8. What NOT to repair manually

- Never `UPDATE inventory.inventory_balances.reserved_stock` to "make the numbers
  match". A hand-edited projection silently disagrees with the reservation rows again
  on the next scan and destroys the audit trail.
- Never delete or edit `stock_transactions` rows. The ledger is append-only history.
- Never release a `CRITICAL` finding (`DUPLICATE_CONSUMPTION_LEDGER`,
  `RELEASE_AND_CONSUMPTION_CONFLICT`, `CONVERTED_RESERVATION_WITHOUT_TARGET`,
  `NEGATIVE_BALANCE_STATE`, `RESERVATION_STORE_MISMATCH`) with a raw write. Those are
  symptoms of an upstream bug and need a real, ledgered business action or a code fix.

## 9. Collect evidence without exposing customer / payment data

Share identifiers only — `runId`, `findingId`, `reservationId`, `orderId`,
`anomaly_type`. The reservation-detail and findings APIs return operational fields
exclusively (status, store, source type, snapshot quantities, ledger ids, anomaly
types) and contain no customer name, email, phone or payment instrument. Do not paste
raw `identity.users`, `customer.customer_profiles` or `payment.payments` rows into a
ticket or the reconciliation trail.
