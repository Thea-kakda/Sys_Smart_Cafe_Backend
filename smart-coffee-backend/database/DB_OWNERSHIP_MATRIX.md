# SMART Coffee & Bakery Database Ownership Matrix

**Status:** Proposed for architecture approval  
**Architecture:** Modular monolith first, microservice-ready  
**Rule:** Every table has exactly one write owner.

## Ownership principles

1. Modules may read or invoke another module only through an explicit application interface.
2. Modules must not inject another module's JPA repository.
3. During the modular-monolith phase, foreign keys may exist between schemas in the same PostgreSQL database.
4. After service extraction, cross-service foreign keys must be removed and replaced by logical UUID references.
5. Historical order, payment, inventory, loyalty, and audit data must not be cascade-deleted.
6. Reporting owns projections only and never modifies operational records.

## Existing table ownership

| Table | PostgreSQL schema | Owning module | Aggregate or purpose | Migration action | Future extracted service |
|---|---|---|---|---|---|
| stores | store | Store | Store | Modify | Store or Catalog Service |
| store_opening_hours | store | Store | Store child | Modify | Store or Catalog Service |
| users | identity | User | User | Modify | Identity Service |
| user_credentials | identity | Auth | Credential | Modify | Identity Service |
| auth_sessions | identity | Auth | AuthSession | Modify | Identity Service |
| roles | identity | RBAC | Role | Keep | Identity Service |
| permissions | identity | RBAC | Permission catalog | Modify | Identity Service |
| role_permissions | identity | RBAC | Role grants | Keep | Identity Service |
| user_roles | identity | RBAC | Store-scoped assignment | Modify | Identity Service |
| customer_profiles | customer | Customer | CustomerProfile | Modify | Customer Service |
| staff_profiles | identity | User | StaffProfile | Modify | Identity Service |
| product_categories | catalog | Catalog | Category | Modify | Catalog Service |
| products | catalog | Catalog | Product | Modify | Catalog Service |
| store_products | catalog | Catalog | Store product availability | Modify | Catalog Service |
| customization_groups | catalog | Customization | CustomizationGroup | Modify | Catalog Service |
| customization_options | catalog | Customization | CustomizationOption | Modify | Catalog Service |
| product_customization_groups | catalog | Customization | Product group assignment | Keep | Catalog Service |
| product_customization_options | catalog | Customization | Product option assignment | Modify | Catalog Service |
| promotions | promotion | Promotion | Promotion | Modify | Promotion Service |
| promotion_products | promotion | Promotion | Product eligibility | Keep | Promotion Service |
| promotion_categories | promotion | Promotion | Category eligibility | Keep | Promotion Service |
| carts | cart | Cart | Cart | Modify | Cart or Ordering Service |
| cart_items | cart | Cart | Cart line | Modify | Cart or Ordering Service |
| cart_item_customizations | cart | Cart | Cart customization | Modify | Cart or Ordering Service |
| orders | ordering | Ordering | Order | Modify | Ordering Service |
| order_items | ordering | Ordering | Order line snapshot | Modify | Ordering Service |
| order_item_customizations | ordering | Ordering | Option snapshot | Modify | Ordering Service |
| order_status_history | ordering | Ordering | Order transition history | Modify | Ordering Service |
| payment_methods | payment | Payment | PaymentMethod | Modify | Payment Service |
| payments | payment | Payment | Payment obligation | Split and modify | Payment Service |
| receipts | payment | Payment | Receipt | Modify | Payment Service |
| units_of_measure | inventory | Recipe | UnitOfMeasure | Modify | Inventory Service |
| ingredients | inventory | Recipe | Ingredient | Modify | Inventory Service |
| product_recipes | inventory | Recipe | Recipe requirement | Replace with versioned recipe | Inventory Service |
| inventory_balances | inventory | Inventory | Current balance projection | Modify | Inventory Service |
| stock_transactions | inventory | Inventory | Stock ledger header | Modify, append-only | Inventory Service |
| stock_transaction_lines | inventory | Inventory | Stock ledger lines | Modify, append-only | Inventory Service |
| expense_categories | expense | Expense | ExpenseCategory | Keep | Expense Service or Operation Service |
| expenses | expense | Expense | Expense | Modify | Expense Service or Operation Service |
| system_settings | setting | Setting | Scoped setting | Split by owner | Remains with owning service |
| customer_favorites | customer | Customer | Favorite | Keep | Customer Service |
| audit_logs | audit | Audit | Append-only audit record | Modify | Audit Service or service-local audit |
| notifications | notification | Notification | Notification | Modify | Notification Service |
| user_notifications | notification | Notification | Recipient delivery | Split and modify | Notification Service |
| device_tokens | notification | Notification | Device endpoint | Modify | Notification Service |

## New required table ownership

| Table | PostgreSQL schema | Owning module | Purpose | Initial priority |
|---|---|---|---|---|
| store_product_prices | catalog | Catalog | Effective-dated store pricing | Required |
| recipe_versions | inventory | Recipe | Effective recipe versions | Required |
| recipe_version_lines | inventory | Recipe | Ingredient requirements | Required |
| api_idempotency_records | integration | Integration / command owner | Durable request idempotency | Required |
| outbox_events | integration | Event producer | Durable event publication | Required |
| consumer_inbox | integration | Event consumer | Event deduplication | Required |
| payment_attempts | payment | Payment | Provider/tender attempt lifecycle | Required |
| payment_provider_callbacks | payment | Payment | Callback evidence and replay protection | Required |
| refunds | payment | Payment | Refund aggregate | Required |
| refund_items | payment | Payment | Refunded order allocation | Required |
| payment_reconciliations | payment | Payment | Unknown-state reconciliation | Required for KHQR |
| inventory_reservations | inventory | Inventory | Stock reservation with one owner at a time: cart line before checkout, order after the Slice 2D conversion | Required |
| inventory_reservation_lines | inventory | Inventory | Ingredient reservation lines | Required |
| inventory_reconciliation_runs | inventory | Inventory | Slice 2E reconciliation scan header: store-or-global scope, dry-run flag, counters, initiating user; a report record, never an operational balance | Required (shipped V047) |
| inventory_reconciliation_findings | inventory | Inventory | Slice 2E per-anomaly report rows (one run to many findings): detector type, severity, expected/actual, repairable status and repair audit; report only — the repair itself acts through the finalization primitives, not by writing balances | Required (shipped V047) |
| ingredient_lots | inventory | Inventory | Expiry and lot costing | Required for perishables |
| promotion_redemptions | promotion | Promotion | Usage limit and discount history | Required |
| loyalty_accounts | customer | Loyalty | Loyalty account | Required if loyalty ships |
| loyalty_ledger | customer | Loyalty | Immutable points movement | Required if loyalty ships |
| notification_delivery_attempts | notification | Notification | Provider attempts and retries | Required |
| customer_consents | customer | Customer | Consent history | Required for marketing |
| offline_pos_operations | ordering | Ordering | POS sync idempotency and conflict state | Required if offline POS ships |
| kitchen_tickets | kitchen | Kitchen | Preparation flow | Required if KDS ships |
| kitchen_ticket_items | kitchen | Kitchen | Preparation line state | Required if KDS ships |
| suppliers | procurement | Procurement | Supplier master | Later or required by scope |
| purchase_orders | procurement | Procurement | Purchase order | Later or required by scope |
| purchase_order_lines | procurement | Procurement | Purchase order lines | Later or required by scope |
| goods_receipts | procurement | Procurement | Received stock | Later or required by scope |
| goods_receipt_lines | procurement | Procurement | Received ingredient lots | Later or required by scope |
| stock_transfers | procurement | Procurement | Store transfer | Later or required by scope |
| stock_transfer_lines | procurement | Procurement | Transfer lines | Later or required by scope |
| pos_registers | pos | POS | Register identity | Required for cash control |
| cashier_shifts | pos | POS | Cashier session | Required for cash control |
| cash_drawer_movements | pos | POS | Cash movement ledger | Required for cash control |

## Cross-service logical references after extraction

The following columns remain UUIDs but lose physical foreign keys after their owning modules become separate services:

- `identity.user_roles.store_id`
- `identity.staff_profiles.home_store_id`
- `customer.customer_profiles.user_id`
- `ordering.orders.customer_id`, `cashier_user_id`, and `created_by`
- `payment.payments.order_id` and `received_by`
- `inventory.stock_transactions.recorded_by`
- `expense.expenses.recorded_by` and `voided_by`
- `notification.notifications.created_by` and recipient `user_id`
- `audit.audit_logs.actor_user_id` and `store_id`

The target service validates these references through authenticated claims, APIs, or replicated reference data. It must never write into the source service database.

## Approval checklist

- [ ] Every original table appears exactly once above
- [ ] Every new table has one write owner
- [ ] Store-sensitive ownership is accepted
- [ ] Payment tables are isolated from Order lifecycle state
- [ ] Inventory balance is not treated as the immutable ledger
- [ ] Promotion and loyalty use durable ledgers
- [ ] Reporting tables remain downstream projections
- [ ] Cross-service foreign-key removal plan is accepted

## Implemented read-model ownership (wiring §1)

The read-only cross-aggregate native queries added for the REST wiring do not change any write ownership; they are recorded here so the "one write owner, explicit read interfaces" rule stays auditable:

| Read model | Owning module (data) | Reader | Writes? |
|---|---|---|---|
| `UserDirectoryQueryRepository` (identity.users ⋈ assignments, customer.customer_profiles flag/slice) | Identity (customer slice owned by Customer) | `GET /api/v1/management/users[/{userId}]` | No — `Repository<T>` is read-only; credential tables are never joined. |
| `StockMovementQueryRepository` (inventory.stock_transactions ⋈ lines ⋈ ingredients ⋈ units) | Inventory | `GET /api/v1/stores/{storeId}/inventory/movements` | No. `stock_transactions.reason` (V030) is written only by the Inventory module through `StockTransactionRepository`. |
| Receipt composition (`payment.receipts` via `ReceiptRepository`, order visibility via `PaymentOrderPort`) | Payment (receipts), Ordering (orders) | `GET /api/v1/orders/{orderId}/receipt` | No — the payment module reads orders only through its port (JPA adapter), never a foreign JPA repository for authorization shortcuts. |
| Order command side effects (cancel releases reservations) | Inventory owns `inventory_reservations` | Ordering invokes `InventoryReservationFinalizationService.releaseOrder(orderId, ORDER_CANCELLED, actor)` (application interface) | Ordering never writes reservation rows itself — interface call only, per ownership principle 1. A reservation that settlement already consumed is left untouched. |
| Checkout ownership conversion and settlement consumption | Inventory owns `inventory_reservations`, `inventory_balances` and the `SALE_RESERVATION`/`SALE_RELEASE`/`SALE_CONSUMPTION` ledger rows (V045/V046) | Cart invokes `CartReservationService` (apply/releaseItem/releaseCart); Ordering invokes `InventoryReservationFinalizationService.convert(storeId, cartId, orderId, ...)`; Payment invokes `InventoryReservationFinalizationService.consumeOrder(orderId, actor)` from the settlement transaction | No — Cart and Ordering pass an already-computed demand map or a cart id; the Inventory module alone decides how much is held, in which lock order, when ownership moves, and writes the ledger. Conversion is metadata-only, so no release is ever written for converted ownership. |
| Slice 2E recovery, reconciliation, expiration and admin release | Inventory owns `inventory_reservations` (including the V047 `processing_*` claim columns), `inventory_balances`, the `SALE_RELEASE`/`SALE_CONSUMPTION` ledger and the two `inventory_reconciliation_*` report tables | Cart invokes `CartExpirationService`/`CartExpiryProcessor` (releases an expired cart hold through `InventoryReservationFinalizationService.releaseCart`); Payment invokes `OrderReservationExpiryService` (claims expired order holds via `ReservationExpiryClaimRepository` `FOR UPDATE SKIP LOCKED`, then `releaseOrder`/`PaidOrderRecoveryService.recover`); the management API invokes `InventoryReconciliationService` (scan/dry-run/repair) and `AdminReservationReleaseService` (single-hold release); `ReservationRepairService` applies an auto-repair only through the same guarded `releaseOrder`/`consumeOrder` primitives | No — the expiry workers and reconciliation never recalculate or overwrite a balance directly. Cart/Payment/management pass an id and a reason; the Inventory module locks the row, revalidates live state, moves `reserved_stock` and writes exactly one terminal ledger row (`SALE_RELEASE` XOR `SALE_CONSUMPTION`, never both). The `processing_*` claim columns are written only by the Inventory claim repository, so two nodes cannot process one reservation. |
