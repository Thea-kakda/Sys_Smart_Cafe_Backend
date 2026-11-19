# Smart Coffee & Bakery — Full-Stack MVP Backend API Handoff

Authoritative contract reference for the four frontends (Admin Dashboard, POS,
Kitchen/Bakery Display, Customer Web). Every field below is a **real, shipped**
response/request field produced by the running backend; nothing here is a
placeholder or a planned-but-unbuilt shape. Items that are known to be missing
are listed explicitly in [Backend API Gaps](#backend-api-gaps) and are **not**
described as available.

- Live OpenAPI document: `GET /v3/api-docs` (public, no auth).
- Swagger UI: `/swagger-ui` (public).
- A CI gate (`OpenApiContractIT`) boots the application and asserts every
  endpoint in this file is present in the published document.

---

## 1. Global conventions

### 1.1 Response envelope
Every endpoint (except actuator, `/v3/api-docs`, `/swagger-ui`) returns:

```json
{ "success": true, "data": <payload>, "requestId": "<uuid>", "timestamp": "<ISO-8601>" }
```

`requestId` is the correlation id (`CorrelationContext.requestId()`); echo it
back on bug reports. Errors use the same envelope shape with `success:false` and
an error body carrying a stable machine `code`.

### 1.2 Pagination
List endpoints return `data` as:

```json
{ "items": [ ... ], "pagination": { "page": 0, "size": 20, "totalElements": 137, "totalPages": 7, "first": true, "last": false } }
```

Page is 0-based. Sort is server-whitelisted; an unknown `sort` is rejected with
`400 INVALID_SORT` rather than silently ignored.

### 1.3 Authentication & store scope
- All `/api/v1/management/**`, `/api/v1/currency-conversions/**`,
  `/api/v1/payments/**`, `/api/v1/users/**`, `/api/v1/production/**` require a
  valid Bearer access token. Missing/expired token ⇒ `401`.
- Public, unauthenticated reads remain only under `/api/v1/stores/**`,
  `/api/v1/categories/**`, `/api/v1/products/**` (the customer-facing catalog);
  these never expose management/pricing-override/administrative fields.
- **Backend is authoritative for `storeId`, permissions, prices, tax, currency
  and station routing.** A client-supplied id is always re-checked against the
  caller's accessible branches. Accessing a branch you are not assigned to ⇒
  `403 STORE_ACCESS_DENIED`; missing permission ⇒ `403 AUTH_FORBIDDEN`.

### 1.4 Optimistic locking (`version`)
Every mutable aggregate returns a `version`. Update/status commands **must**
echo the current `version`; a stale value ⇒ `409 VERSION_CONFLICT`. This prevents
lost writes when two administrators edit the same branch/product.

### 1.5 Create-vs-update and null semantics
- **Create** commands accept no `version` (row is created at version 0). Creating
  a duplicate identity ⇒ `409 CONFIGURATION_EXISTS` (branch-product) / store-code
  conflict (branch).
- **Update** commands are full replacements (`PUT`). `null` is meaningful:
  `overridePrice = null` ⇒ *inherit* base price; `soldOutUntil = null` ⇒ *clear*
  the sold-out window. Required fields (`available`, `stationCode`) are validated
  non-null so an omitted field can never silently reset routing/availability.

### 1.6 Money & rounding
All money is `BigDecimal`, serialized as a decimal string; the DB stores
`numeric(14,4)`. Never use `float`/`double`. Currency is an ISO-4217 3-letter
code. The backend allowlist of currencies is exactly `USD`, `KHR`
(`payment.domain.Currency`); anything else ⇒ `400 UNSUPPORTED_CURRENCY`. Each
currency has fixed minor units used for rounding: **USD = 2 decimals, KHR = 0
decimals**. Rounding is `HALF_UP` to the target currency's minor units and is
reported in responses (`roundingMode`, `roundingAmount`).

### 1.7 Error code → HTTP status

| Code | HTTP | Meaning |
|------|------|---------|
| `AUTH_FORBIDDEN` | 403 | Missing required permission |
| `STORE_ACCESS_DENIED` | 403 | Branch not in caller's accessible set |
| `UNAUTHORIZED` / auth required | 401 | No/invalid token |
| `ResourceNotFoundException` | 404 | Unknown id |
| `ValidationException` (`INVALID_SORT`, `UNSUPPORTED_CURRENCY`, `INVALID_STATION`, `NEGATIVE_AMOUNT`, `AMOUNT_REQUIRED`, …) | 400 | Client input rejected |
| `CONFIGURATION_EXISTS`, `VERSION_CONFLICT`, `RATE_STALE`, `RATE_UNAVAILABLE`, `INSUFFICIENT_CASH_RECEIVED`, `ORDER_ALREADY_PAID` | 409 | State/conflict rejection |

### 1.8 Idempotency
Money-mutating and workflow commands require an `Idempotency-Key: <uuid>` header.
Replaying the **same** key + body returns the original stored response (no second
charge, no second ticket transition). Reusing a key with a **different** body is
rejected. Idempotency records: cash 30 days, KHQR 7 days, workflow commands
scoped per user.

---

## 2. Access context

### `GET /api/v1/users/me/access-context`
Auth: any authenticated user. The single source of truth for what a logged-in
user may do and where. Frontends must derive role/store selection from this and
**never** invent permissions client-side.

Response `data` (`AccessContextResponse`):

| Field | Type | Notes |
|-------|------|-------|
| `userId` | uuid | |
| `fullName` | string | |
| `email` | string | |
| `preferredLanguage` | string | e.g. `en-US` |
| `status` | string | account status |
| `roles` | string[] | effective role names |
| `permissions` | string[] | resolved permission codes (see §5) |
| `globalAccess` | boolean | true ⇒ may reach every branch |
| `accessibleBranches` | array | see below |

`accessibleBranches[]`: `storeId`, `storeCode`, `storeName`, `timezone`,
`defaultCurrency`, `defaultLanguage`, `active`.

> The persisted "last selected store" in the browser is only a **preference**; it
> is valid only if its id appears in `accessibleBranches`.

---

## 3. Branch (Store) management

Base `/api/v1/management/stores`. `branch:read` for reads, `branch:manage` for
mutations. The UI term is "Branch"; the technical entity is "Store". These never
fabricate an `open` flag.

| Method | Path | Perm | Notes |
|--------|------|------|-------|
| GET | `/api/v1/management/stores` | branch:read | query `search`, `active`, `page`, `size`; paginated |
| GET | `/api/v1/management/stores/{storeId}` | branch:read | detail |
| POST | `/api/v1/management/stores` | branch:manage | create |
| PUT | `/api/v1/management/stores/{storeId}` | branch:manage | full-replace profile (requires `version`) |
| POST | `/api/v1/management/stores/{storeId}/status` | branch:manage | activate/deactivate (requires `version`) |

**`ManagementStoreResponse`**: `storeId`, `storeCode`, `storeName`, `address`,
`phone`, `timezone`, `defaultCurrency`, `defaultLanguage`, `active`,
`createdAt`, `updatedAt`, `version`.

**Create `CreateStoreRequest`**: `storeCode`* (≤30), `storeName`* (≤150),
`address`* (≤2000), `phone` (≤30), `timezone`* (≤64, IANA), `defaultCurrency`*
(exactly 3), `defaultLanguage`* (≤10), `active` (optional, default true).
`storeCode` is immutable business identity.

**Update `UpdateStoreRequest`**: same as create minus `storeCode`, plus
`version`* (required, ≥0).

**Status `StoreStatusRequest`**: `active`* (bool), `version`* (≥0).

---

## 4. Branch products (offer, pricing, availability, station routing)

Base `/api/v1/management/stores/{storeId}/store-products`. `branch-product:read`
for reads, `branch-product-manage` for mutations. All price/availability/station
values are authoritative backend outputs; the frontend renders, never recomputes.

| Method | Path | Perm | Notes |
|--------|------|------|-------|
| GET | `/…/store-products` | branch-product:read | filters `search`,`categoryId`,`productActive`,`branchAvailable`,`availability`,`overrideState`,`stationCode`; sort (whitelist, default `productName`), `direction`,`page`,`size` |
| GET | `/…/store-products/{productId}` | branch-product:read | detail |
| POST | `/…/store-products` | branch-product:manage | add product to branch (create) |
| PUT | `/…/store-products/{productId}/configuration` | branch-product:manage | full-replace configuration |

**`ManagementStoreProductResponse`**: `productId`, `productCode`, `productName`,
`categoryId`, `categoryName`, `basePrice`, `overridePrice`, `inheritedPrice`,
`effectivePrice`, `currencyCode`, `productActive`, `branchAvailable`,
`effectiveAvailable`, `soldOutUntil`, `stationCode`, `preparationMinutes`,
`customizable`, `productVersion`, `storeProductVersion`, `updatedAt`.

**Pricing rules**
- `overridePrice` null ⇒ `inheritedPrice=true` and `effectivePrice = basePrice`.
- `overridePrice` set ⇒ `effectivePrice = overridePrice`.
- Negative price ⇒ `400 NEGATIVE_PRICE`; scale over money precision ⇒
  `400 PRICE_SCALE_EXCEEDED`; a branch currency different from the product's ⇒
  `400 CURRENCY_MISMATCH`.

**Availability rules**
- `effectiveAvailable = branchAvailable AND (soldOutUntil IS NULL OR soldOutUntil < now())` —
  identical to the public catalog projection (single source of truth in SQL + domain).

**Station rules**
- `stationCode` is **required on create and update** so a new configuration never
  depends on the legacy production-routing fallback. Invalid/inactive station ⇒
  `404 STATION_NOT_FOUND` / `400 STATION_INACTIVE` / `400 STATION_REQUIRED`.

**Create `CreateStoreProductRequest`**: `productId`*, `overridePrice`, `available`*
(bool), `soldOutUntil`, `stationCode`* (1–40). Duplicate ⇒ `409 CONFIGURATION_EXISTS`.

**Update `UpdateStoreProductConfigurationRequest`**: `overridePrice`,
`available`*, `soldOutUntil`, `stationCode`*, `storeProductVersion`*. Stale ⇒
`409 VERSION_CONFLICT`.

---

## 5. Production — stations & queues

### 5.1 Station discovery (management)
`GET /api/v1/management/stores/{storeId}/production/stations` — auth +
`production:station-read` + store access. Default is **active-only**;
`?includeInactive=true` additionally requires `production:station-manage`.
Returns a `ProductionStationResponse[]` ordered by `displayOrder`:

`stationId`, `storeId`, `stationCode`, `stationName`, `stationType`
(`BARISTA|KITCHEN|BAKERY|PACKING|PICKUP`), `displayOrder`, `active`, `version`.

### 5.2 Queue & workflow (station staff)

| Method | Path | Perm | Notes |
|--------|------|------|-------|
| GET | `/api/v1/stores/{storeId}/production/{station}` | production:queue-read | queue for a station |
| POST | `/api/v1/production/ticket-items/{itemId}/start` | production:item-start | Idempotency-Key required |
| POST | `/api/v1/production/ticket-items/{itemId}/ready` | production:item-ready | Idempotency-Key required |

`{station}` is parsed via `StationType.parse`; an unknown station is a
**`400 INVALID_STATION`**, never an unhandled `500`. Station routing for produced
items comes from the branch-product configuration; the `BARISTA` fallback exists
only as a documented legacy path and is logged (`log.warn`) — new configurations
must set a station explicitly.

**`ProductionTicketResponse`**: `ticketId`, `ticketNumber`, `orderId`, `storeId`,
`stationId`, `stationType`, `status`, `priority`, `queuedAt`,
`items[]` (`ProductionItemResponse`), `version`.

---

## 6. Currency conversion

### 6.1 Preview (read-only quote)
`POST /api/v1/currency-conversions/preview` — auth + `currency-conversion:preview`
+ store access. It computes and reserves **nothing**; it is a quote only. The
client sends intent only; the selected rate, direction and rounding are decided
by the backend and cannot be influenced by the caller.

**Request `CurrencyConversionPreviewRequest`**: `storeId`*, `sourceCurrency`*,
`targetCurrency`*, `amount`* (≥0).

**Response `CurrencyConversionResponse`** (full decision trail):
`sourceAmount`, `sourceCurrency`, `targetAmount`, `targetCurrency`,
`exchangeRateId`, `appliedRate`, `baseCurrency`, `quoteCurrency`,
`inversionUsed`, `rateSource`, `effectiveAt`, `validUntil`, `roundingMode`,
`targetScale`, `serverTimestamp`.

**Rate direction** — a rate row means `1 baseCurrency = rate quoteCurrency`
(e.g. `USD→KHR 4100` ⇒ 1 USD = 4100 KHR). Converting against the stored
direction uses the rate directly; converting the other way **inverts** it and
sets `inversionUsed=true`. Same-currency ⇒ identity (`appliedRate=1`,
`exchangeRateId=null`, `rateSource="IDENTITY"`).

**Rate authority (selection rules)**
- Branch-specific rate wins over a global (`store_id` null) rate.
- Deterministic at an instant; latest effective window wins.
- A missing / expired / not-yet-effective rate for differing currencies is a
  hard `409 RATE_UNAVAILABLE`. There is **no silent fallback to 1**.
- `400 NEGATIVE_AMOUNT` / `400 AMOUNT_REQUIRED` guard the input;
  `400 UNSUPPORTED_CURRENCY` for non-allowlisted codes.

### 6.2 Rate table (data model — V019 `payment.currency_exchange_rates`)
Columns: `exchange_rate_id`, `store_id` (null = global), `base_currency`,
`quote_currency`, `rate numeric(20,10)`, `source`, `effective_from`,
`effective_to` (null = open), `active`, `created_by_user_id`, timestamps,
`version`. Invariants enforced in the domain: base ≠ quote, rate > 0, rate scale
≤ 10, effectiveTo > effectiveFrom, no physical delete of a used rate, optimistic
locking. **Operational management endpoints for this table are not yet built** —
see [Backend API Gaps](#backend-api-gaps).

---

## 7. Payments

Base `/api/v1/payments`. All require auth + `Idempotency-Key`.

### 7.1 Cash — `POST /api/v1/payments/cash`
The backend is authoritative for settlement. The client never dictates the rate.

**Request `CashPaymentRequest`**: `orderId`*, `storeId`*, `amountReceived`*
(>0, in `tenderCurrency`), `tenderCurrency`*, `exchangeRate`
(**DEPRECATED**, see below).

Flow: load the order's branch + currency + total, convert `amountReceived` from
`tenderCurrency` to the order currency using the **active authoritative rate**,
then compare. If the converted value < order total ⇒ `409
INSUFFICIENT_CASH_RECEIVED`.

**Deprecated `exchangeRate` (compat for one release):** it is **never** used for
settlement; it is treated only as an expected preview and compared against the
effective multiplier (`settlement / amountReceived`). A deviation beyond 0.1%
tolerance ⇒ `409 RATE_STALE`. This closes the P0 defect where a fabricated rate
could let a cashier settle an order with insufficient tender.

**Change rule:** the order is settled in its own currency, but physical change is
handed back in the currency the customer **tendered** (`changeCurrency =
tenderCurrency`). Change in the tender currency is derived from the same applied
rate/inversion used for settlement (no second rate lookup), rounded HALF_UP to
the tender's minor units.

### 7.2 `PaymentResponse`
`paymentId`, `paymentReference`, `orderId`, `method`, `status`, `currencyCode`
(order/settlement currency), `amount` (order total), `amountReceived`
(settlement value), `changeAmount`, `providerReference`, `qrPayload`,
`expiresAt`, `paidAt`, `failureCode`, `idempotentReplay`, `conversion`.

`conversion` is `PaymentConversionResponse` (cash) or `null` (e.g. KHQR):

**`PaymentConversionResponse`** — the immutable 5D snapshot:
`tenderCurrency`, `tenderAmountReceived`, `exchangeRateId`,
`appliedExchangeRate`, `rateBaseCurrency`, `rateQuoteCurrency`,
`exchangeRateInverted`, `settlementAmountReceived`, `settlementChangeAmount`,
`tenderChangeAmount`, `changeCurrency`, `roundingAmount`.

### 7.3 KHQR
`POST /api/v1/payments/khqr` (create) and `POST /api/v1/payments/{paymentId}/verify`
(provider verification → order confirmation). Same envelope; `conversion` is
`null` for KHQR (no cash tender decision).

---

## 8. Receipts

A receipt is created automatically when a payment succeeds (`payment.receipts`,
immutable `receipt_snapshot` jsonb). It is **self-describing**: because the
payment snapshot is denormalized with **no foreign key to the live rate table**,
a later rate change can never alter an old payment, receipt, change, audit record
or idempotent replay.

`receipt_snapshot` keys (cash): `orderNumber`, `paymentReference`, `orderId`,
`storeId`, `orderCurrency`, `amount`, `currency`, `total`, `taxAmount`,
`paymentStatus`, `paidAt`, and the conversion trail `tenderCurrency`,
`tenderAmountReceived`, `exchangeRateId`, `appliedExchangeRate`,
`rateBaseCurrency`, `rateQuoteCurrency`, `rateDirection` (`"BASE->QUOTE"`),
`exchangeRateInverted`, `settlementAmountReceived`, `settlementChangeAmount`,
`tenderChangeAmount`, `changeAmount`, `changeCurrency`, `roundingAmount`.

---

## 9. Permission codes

`branch:read`, `branch:manage`, `branch-product:read`, `branch-product:manage`,
`production:station-read`, `production:station-manage`, `production:queue-read`,
`production:item-start`, `production:item-ready`, `currency-conversion:preview`,
`currency-rate:read`, `currency-rate:manage`, `pos:access`, `order:read`,
`inventory:read`, `inventory:adjust`, `audit:read`.

---

## Backend API Gaps

Explicitly **not yet implemented** (do not consume these as available):

1. **Operational exchange-rate CRUD** (Dashboard rate management + converter,
   Checkpoint 10). The `currency_exchange_rates` table, the domain guards and the
   `currency-rate:read` / `currency-rate:manage` permission codes exist, and the
   conversion **preview** endpoint is live, but there is **no** HTTP endpoint yet
   to list/create/update/close rate rows. Rates are currently inserted via
   migration/DB only.
2. **Standalone pricing endpoint.** Pricing is managed through the
   branch-product configuration API (§4); there is no separate `/pricing`
   resource.
3. **Receipt download/render endpoint.** Receipts are persisted as immutable
   snapshots; a dedicated GET/render/object-storage endpoint is not yet exposed.

Frontend checkpoints (Dashboard, POS, KDS/BDS, Customer Web) must be built
against the endpoints above and must not assume any field listed under Gaps.
