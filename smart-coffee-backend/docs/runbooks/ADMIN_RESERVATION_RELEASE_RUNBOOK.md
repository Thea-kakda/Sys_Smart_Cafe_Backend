# Administrative Reservation Release Runbook (Slice 2E)

**Audience:** store managers and administrators.
**Purpose:** safely give back **one** abandoned `ACTIVE` reservation hold through an
audited, permission- and scope-gated endpoint — instead of a raw `UPDATE`.

This is a scalpel, not a batch tool. For bulk cleanup use the reconciliation repair
pass (`RESERVATION_RECOVERY_RUNBOOK.md`). Reach for the admin release when a single
known-stuck hold must be given back now.

## 1. When to use it

- A hold is `ACTIVE` and provably will never be settled or consumed (an abandoned,
  unpaid order the customer walked away from, a stuck test order).
- You can identify the exact `reservationId` and its current `version`.

**Do not** use it to "correct" a balance mismatch, to release a paid order (that is
recovery, which consumes — never releases), or on a hold you have not inspected.

## 2. Permissions and scope

- Capability: `reservation:release`.
- **Store scope is enforced server-side.** A `STORE_MANAGER` releases only holds in
  their own stores; a cross-store hold returns `403 STORE_ACCESS_DENIED` and mutates
  nothing. Global reach is a SUPER_ADMIN/ADMIN property.
- The application service re-checks capability and scope even though the endpoint also
  does — the HTTP layer is not the guard.

## 3. The call

```
POST /api/v1/management/inventory/reservations/<reservationId>/release
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: <uuid>                       # header wins over the body field

{
  "expectedVersion": <long>,                  # the reservation version you read
  "reason": "abandoned unpaid hold from the 12/10 incident",   # required, non-blank
  "idempotencyKey": "<uuid>"                  # optional if sent as the header
}
```

- **`reason` is mandatory** (`@NotBlank`). It lands on the `SALE_RELEASE` ledger row
  and the audit record. A blank reason is a `400`.
- **`expectedVersion`** is your optimistic-lock token: pass the `version` you read from
  the reservation detail. A mismatch means someone else moved the row first — you get a
  `409`, never a silent overwrite. Omit it only if you deliberately accept whatever the
  current state is (the ACTIVE/consumed checks still apply).
- **`Idempotency-Key`** makes a retried call replay the original result instead of
  releasing twice. Reuse the same key across retries of the *same* intent.

## 4. Outcomes

| Situation | HTTP result | Effect |
|---|---|---|
| Hold is `ACTIVE`, in your store scope | `200`, `released = true` | `reserved_stock` falls by the exact snapshot; `current_stock` unchanged; **one** `SALE_RELEASE` written. |
| Hold already `RELEASED`/terminal | `200`, `released = false` | Stable idempotent no-op, no second ledger row. |
| Hold is `CONSUMED` | `409` `RESERVATION_CONSUMED_CANNOT_RELEASE` | Nothing changes. Consumed stock can never be given back. |
| Order behind the hold is settled/paid | `409` `RESERVATION_SETTLED_CANNOT_RELEASE` | Nothing changes — go to recovery, not release. |
| Hold is a `CONVERTED` cart line | `409` (converted cannot release) | The order target owns the stock; releasing the cart row would double-count. |
| Wrong store / no permission | `403` | Nothing changes. |
| No/blank reason | `400` | Nothing changes. |
| `expectedVersion` stale | `409` (version conflict) | Nothing changes; re-read the reservation and retry. |

A released hold is terminal: a second release call (same or different key) is a
no-op and writes no additional ledger row.

## 5. Verify afterward

1. `GET /api/v1/management/inventory/reservations/<reservationId>` → status is
   `RELEASED`, `releaseReason` matches, and exactly one related `SALE_RELEASE` ledger
   id is present.
2. The store's `reserved_stock` for the affected items dropped by the snapshot amount;
   `current_stock` is unchanged.
3. A follow-up **dry-run** reconciliation for that store reports no new
   `RELEASED_RESERVATION_WITHOUT_RELEASE_LEDGER` (the release has its ledger row).

## 6. Guardrails

- **Never** release a hold belonging to an order that was paid — that returns stock
  that sold. If the order is paid and unconsumed, that is the recovery path
  (consume), not a release.
- **Never** use this endpoint to force `reserved_stock` to a number. It only ever acts
  on a real `ACTIVE` reservation through the guarded primitive.
- If the same hold seems to need releasing repeatedly, stop — an upstream worker or
  transaction is failing to finalize it; escalate rather than keep clicking.
