# Reservation Metrics & Alerts (Slice 2E)

**Source of truth:** `ReservationMetrics` (all meter names declared once) and
`ReservationHealthIndicator`. Exported through Micrometer at `/actuator/metrics` and
the health component at `/actuator/health`.

**Cardinality rule (non-negotiable):** no metric ever carries a `store` id or a
`reservation` id as a label. Identifiers belong in structured logs, not metric tags.
The only allowed tags have small fixed domains: `outcome`, `sourceType`,
`anomalyClass`, `dryRun`, `severity`, `reason`, `class`.

## Counters

| Meter | Meaning | Suggested alert |
|---|---|---|
| `reservation.expired.processed` | order/cart expiry worker processed one candidate | — |
| `reservation.expired.released` | a hold was released by an expiry worker | rate should track `expired.processed` |
| `reservation.expired.failed` | an expiry worker failed a candidate (after bounded retry) | **alert** if > 0 sustained |
| `reservation.reconciliation.runs` | a reconciliation scan ran (tags `dryRun`, `scope`) | — |
| `reservation.reconciliation.anomalies` | findings written by a scan (tag `severity=any`) | rising trend = investigate |
| `reservation.reconciliation.repaired` | a repair pass outcome (tags `outcome`, `dryRun`) | — |
| `reservation.reconciliation.failed` | a repair failed / was demoted (tag `reason`) | **alert** on `reason=manual_review` spikes |
| `reservation.admin_release` | administrative releases performed | — |
| `reservation.retry` | a bounded transient-DB retry attempt | — |
| `reservation.retry_exhausted` | retries exhausted (dead-letter ceiling hit) | **alert** — a reservation is stuck failing |
| `reservation.paid_unconsumed.detected` | a paid-but-unconsumed hold detected (CRITICAL) | **page** — money taken, stock not consumed |
| `reservation.orphan.detected` | an orphan reservation/line/ledger detected (tag `class`) | **alert** on any sustained non-zero |

## Gauges (bounded scheduled snapshots — set, not counted)

Refreshed by the scheduled `ReservationOperationalMonitor`; a health poll reads the
cached snapshot and issues **no** query.

| Meter | Meaning |
|---|---|
| `reservation.active.cart` | ACTIVE cart-owned holds outstanding |
| `reservation.active.order` | ACTIVE order-owned holds outstanding |
| `reservation.expired.pending` | holds past `expires_at` still ACTIVE (worker lagging) |
| `reservation.anomaly.count` | open reconciliation findings (ERROR/CRITICAL backlog) |
| `reservation.balance_mismatch.total` | recorded-vs-derived reserved balance mismatches |

**Alert:** `reservation.expired.pending` > 0 for longer than one worker period means
the cleanup worker is not keeping up. `reservation.anomaly.count` /
`reservation.balance_mismatch.total` rising means real drift is accumulating.

## Timers

| Meter | Meaning |
|---|---|
| `reservation.expiry.duration` | one expiry worker batch duration |
| `reservation.reconciliation.duration` | one scan duration |
| `reservation.repair.duration` | one repair pass duration |

## Health indicator (`reservation`)

`ReservationHealthIndicator` reads only the cached snapshot:

- **UP** — the last bounded check found nothing critical.
- **DEGRADED** — an open `ERROR` finding exists, or expired holds are pending past
  their TTL (worker lagging), within tolerance.
- **DOWN** — an open `CRITICAL` finding exists (duplicate consumption, a
  release/consumption conflict, a critical balance mismatch), **or** the collector has
  gone stale past `staleAfter` (its worker is failing and the numbers can no longer be
  trusted).

Health details are plain counts (`openCritical`, `openError`, `expiredPending`,
`snapshotAgeSeconds`) — never quantities, store ids or reservation ids.

## What to watch, in order

1. `reservation.paid_unconsumed.detected` and any `CRITICAL` finding → page.
2. `reservation.retry_exhausted` / `reservation.expired.failed` → a stuck reservation
   that will not self-heal.
3. `reservation.expired.pending` sustained → worker/scheduler health.
4. `reservation.anomaly.count` / `reservation.balance_mismatch.total` trend → slow
   drift toward an incident.
