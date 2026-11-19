# SMART Coffee & Bakery Schema Changelog

## Purpose

This document records architecture-level schema changes from the supplied PostgreSQL model to the corrected logical model. It is not a Flyway history yet. Flyway migration names will be finalized only after the DBML is approved.

## Baseline: supplied schema

The baseline contains Store, Identity/RBAC, Customer/Staff, Catalog, Customization, Promotions, Cart, Orders, Payments, Receipts, Recipe, Inventory, Expenses, Settings, Favorites, Audit, and Notifications.

## Proposed release 0.1: correctness corrections

### Global changes

- Replace business `timestamp` columns with `timestamptz`.
- Retain local `time` for opening hours and interpret it with `stores.timezone`.
- Replace queryable `json` columns with `jsonb`.
- Add non-negative checks for monetary amounts and balances.
- Add positive checks for quantities.
- Add explicit version columns to mutable high-contention aggregates.
- Add consistent `created_at` and `updated_at` fields.
- Add currency checks for initially supported `USD` and `KHR`.

### Identity and RBAC

- Normalize email and phone before uniqueness checks.
- Extend `auth_sessions` with token family, rotation counter, last-used time, and revocation reason.
- Add active assignment uniqueness for `user_roles`.
- Treat `store_id` as a future logical reference when Identity is extracted.

### Store

- Add unique `(store_id, day_of_week)` to opening hours.
- Add day-of-week and open/close constraints.
- Add latitude and longitude range constraints.

### Catalog and customization

- Move authoritative effective store pricing to `store_product_prices`.
- Keep `store_products` for availability and sold-out state.
- Add composite option/group integrity.
- Add customization min/max checks.
- Keep historical order snapshots even when products are retired.

### Cart and ordering

- Add `AWAITING_PAYMENT` to order state.
- Add `api_idempotency_records` for durable order creation and other critical commands.
- Add positive quantity and non-negative monetary checks.
- Preserve order lines and customization snapshots.
- Keep order state separate from payment/refund state.

### Payment

- Redefine `payments` as the payment obligation/aggregate.
- Add `payment_attempts`.
- Add `payment_provider_callbacks` with unique provider event identity.
- Add `refunds` and `refund_items`.
- Add `payment_reconciliations` for unknown provider results.
- Remove the misleading default payment completion timestamp from pending payments.
- Require provider-side evidence or authorized cash handling before success.

### Promotion and loyalty

- Add `promotion_redemptions` for concurrency-safe limits and discount snapshots.
- Add `loyalty_accounts` and append-only `loyalty_ledger`.
- Treat profile `points_balance` as a cached projection or remove it after migration.

### Recipe and inventory

- Replace mutable product recipe rows with `recipe_versions` and `recipe_version_lines`.
- Add `inventory_reservations` and lines.
- Add `ingredient_lots` for expiry, FEFO allocation, and lot cost.
- Add optimistic version to inventory balances.
- Make stock transaction records append-only.

### Notification

- Keep notification content separate from recipient status.
- Add delivery attempts, retries, provider references, and errors.
- Device token ownership remains with Notification.

### Reliability

- Add `outbox_events` for durable publication.
- Add `consumer_inbox` for idempotent consumption.
- Messages carry event ID, type, version, aggregate ID, correlation ID, causation ID, and occurrence time.

## Proposed release 0.2: operational capability additions

These are implemented only if approved in first-release scope:

- Procurement: suppliers, purchase orders, goods receipts, stock transfers
- POS cash control: registers, cashier shifts, drawer movements
- Kitchen Display: kitchen tickets and ticket items
- Offline POS: durable local-operation receipt and conflict tracking
- Customer privacy: consent history and anonymization request workflow

## Compatibility notes

- Additive tables are backward compatible until application code starts requiring them.
- Changing `timestamp` to `timestamptz` requires a deliberate timezone conversion using the known legacy interpretation.
- Splitting `payments` requires dual-read or controlled cutover if production data already exists.
- Recipe versioning requires creation of an initial version from current `product_recipes`.
- Loyalty ledger migration requires an opening-balance entry for existing points.
- Cross-service foreign keys are retained only while modules share one database.

## Planned Flyway sequence after approval

```text
V1__create_extensions_and_schemas.sql
V2__create_identity_and_rbac.sql
V3__create_store_and_customer.sql
V4__create_catalog_and_customization.sql
V5__create_cart_and_ordering.sql
V6__create_recipe_and_inventory.sql
V7__create_payment.sql
V8__create_promotion_and_loyalty.sql
V9__create_notification.sql
V10__create_expense_setting_audit.sql
V11__create_integration_reliability.sql
V12__create_optional_pos_kitchen_procurement.sql
V13__create_indexes_and_constraints.sql
V14__seed_roles_permissions_and_reference_data.sql
```

Once a migration is released and applied, it must never be edited. Every subsequent change receives a new migration.

## Applied migration log (implementation, Flyway V001–V047)

The sections above are the original design proposal. The applied schema lives in `src/main/resources/db/migration` and evolved beyond that proposal (schemas are `organization`/`customer`/`identity`/`catalog`/`cart`/`ordering`/`payment`/`recipe`/`inventory`/`promotion`/`loyalty`/`notification`/`audit`/`integration`). Selected later entries, newest first:

| Migration | Purpose |
|---|---|
| V047 | Slice 2E reservation recovery, reconciliation, expiration and operational hardening. (1) Multi-node expiry-claim columns on the SAME `inventory.inventory_reservations`: `processing_owner`, `processing_token`, `processing_started_at`, `processing_attempts`, `last_processing_error` and a bounded `reconciliation_status` cache (CHECK `CLEAN/ANOMALY/REPAIRABLE/MANUAL_REVIEW/REPAIRED`), plus partial indexes for the `FOR UPDATE SKIP LOCKED` claim (`WHERE status='ACTIVE' AND source_type='ORDER'`) and stale-claim reclaim. (2) Two NEW reporting tables — `inventory.inventory_reconciliation_runs` (`status varchar(30)` so `COMPLETED_WITH_FINDINGS` fits; counters; `initiated_by` FK `identity.users`; `parameters` jsonb) and `inventory.inventory_reconciliation_findings` (`anomaly_type` CHECK over the 18 detectors; `severity`; `expected/actual_quantity`; `repairable`; `status` `OPEN/REPAIRABLE/REPAIRED/MANUAL_REVIEW/SUPERSEDED/DISMISSED`; repair audit columns) with partial unique `uk_recon_finding_repair_key` and open/run/reservation indexes; the repair-reason CHECK requires a non-blank reason for a `REPAIRED` finding and deliberately permits a null `repaired_by` for a system-initiated batch repair. (3) Four permissions in the `e500…` namespace (`reservation:release`, `reconciliation:view/run/repair`): granted to SUPER_ADMIN/ADMIN as a global cross-join and to STORE_MANAGER for its own store (server-side scope still enforced). Additive and idempotent; no backfill, no balance/ledger mutation. |
| V046 | Slice 2D checkout reservation conversion + `SALE_CONSUMPTION`. Extends the SAME reservation table: `inventory_reservations.converted_at`, `converted_order_id`, `converted_order_line_id`, `converted_to_reservation_id`, `consumed_by`; `ck_reservation_status` re-created with `CONVERTED`; partial unique `uk_reservation_converted_line` (one order line per converted cart hold) and `ix_reservation_converted_order`; `ordering.order_items.cart_item_id` (no FK: cart-line deletes must not destroy sale history) and `ordering.order_items.variant_id` (FK `catalog.product_variants`) with their partial indexes; and the DB backstop `uk_stock_consumption_reservation` (partial unique on `inventory.stock_transactions.reservation_id` where `transaction_type='SALE_CONSUMPTION'`) so one reservation can never be consumed twice. Additive and idempotent; no backfill, no balance/ledger mutation, no edit of V001â€“V045. |
| V045 | Slice 2C cart inventory reservations. Extends the EXISTING `inventory.inventory_reservations` instead of adding a parallel store: `cart_id` (FK), `cart_item_id` (deliberate audit pointer, no FK — cart-line deletes must never destroy reservation history), `demand_revision`, `source_type` (`ORDER`/`CART_ITEM`), `source_recipe_version_id`/`_number`, `resolution_source`, `idempotency_key`, `release_reason`; `order_id` becomes nullable; `ck_reservation_owner` requires an order OR cart owner; `ck_reservation_status` gains `CANCELLED`; partial unique index `uk_reservation_cart_item` (one row per cart line, revised in place) and `ix_reservation_cart_active`. Adds `inventory_reservation_lines.base_unit_code`, `cart.cart_items.variant_id` (nullable, FK + partial index) so a line can resolve a variant recipe, and ledger traceability `stock_transactions.{cart_id, cart_item_id, reservation_id, idempotency_key}` with `ix_stock_transactions_cart`/`_reservation`. Re-creates `ck_stock_type` with `SALE_RESERVATION`/`SALE_RELEASE` while keeping every V005/V033/V035/V038 type. Additive and idempotent; no backfill of pre-existing cart lines, no balance/ledger/order mutation, no `SALE_CONSUMPTION` change. |
| V044 | Slice 2B.1 recipe-assignment correction: `catalog.products.active_recipe_version_id` (FK `fk_product_active_recipe` + partial index `ix_product_active_recipe`), backfilled from each product's prior single ACTIVE version (IDs/quantities untouched), then drops `recipe.uk_recipe_one_active_per_product`. ACTIVE now means *published & assignable*; selection lives in the product/variant pointer columns, never in status. Preflight refuses the migration if a variant pointer references another product's version. |
| V043 | `catalog.product_variants.active_recipe_version_id` variant override pointer (FK), partial unique selector index `uk_recipe_one_active_per_product` (later dropped by V044) and recipe permission seeding (`recipe:view/manage/activate/assign`). |
| V042 | Recipe version extensions: `yield_quantity`/`yield_unit_id`/`notes`/`activated_at`/`activated_by`/`created_by`/`updated_by`/`updated_at` on `recipe.recipe_versions`, line metadata (`unit_id`, `scaling_rule`, `display_order`, `optional`, `notes`) on `recipe.recipe_version_lines`, yield backfill to 1. |
| V030 | `inventory.stock_transactions.reason` (nullable, 500) + `(store_id, occurred_at DESC)` ledger index — manual stock adjustments (wiring §1.5). |
| V029 | Permission `order:admin-write` (namespace `c1000…`) granted to SUPER_ADMIN/ADMIN/STORE_MANAGER — explicit order cancel/complete commands (wiring §1.4). |
| V028 | Permission `user:admin-read` (namespace `bf000…`) granted to SUPER_ADMIN/ADMIN only — global user directory (wiring §1.1); deliberately not STORE_MANAGER to contain customer PII. |
| V027 | Report read permissions (`report:sales-read`, `report:inventory-read`). |
| V026 | Promotion management permissions (`promotion:read`, `promotion:manage`). |
| V025 | `order:admin-read` — management order search/detail. |
| V024 | Role/permission management codes (`role:read`, `role:manage`). |
| V023 | Staff management codes (`staff:create`, `staff:read`, `staff:update`, …). |
| V022 | Seed product recipes for the demo catalog. |
| V020 | Currency conversion preview/manage permissions. |

All migrations are idempotent (`IF NOT EXISTS` / `ON CONFLICT DO NOTHING`) and additive; no released migration has ever been edited.
