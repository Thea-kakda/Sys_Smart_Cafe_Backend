# ADR-005: Money, Currency, Exchange Rate, and Rounding Model

- **Status:** Proposed
- **Decision date:** 2026-08-18
- **Owners:** Architecture, Finance, and Backend Teams
- **Scope:** Prices, payments, refunds, costs, taxes, discounts, and multi-currency handling

## Context

SMART Coffee & Bakery primarily uses USD and must support KHR. Product prices, customization prices, taxes, discounts, costs, payments, refunds, cash received, change, and exchange rates require exact arithmetic and an auditable rounding policy.

Binary floating-point values can introduce precision errors and are not acceptable for financial records.

## Decision

Represent money in Java using an immutable `Money` value object containing:

```text
BigDecimal amount
Currency currency
```

Persist business money values as PostgreSQL `numeric(14,4)` with a three-character ISO-style currency code. Persist exchange rates as `numeric(20,10)`.

Never use `float` or `double` for financial calculations.

The server is authoritative for all calculations. Clients send product IDs, quantities, customization option IDs, payment method, and optional promotion code. Clients do not supply accepted totals.

## Currency Rules

- Every monetary aggregate has an explicit currency.
- An order has one settlement currency.
- Order lines, discounts, taxes, receipts, and refunds use the order settlement currency.
- Tender currency may differ for cash payment.
- Exchange rate is captured as an immutable payment-attempt snapshot.
- USD and KHR are initially accepted only where store/payment configuration permits.
- Cross-currency arithmetic requires explicit conversion.

## Calculation Order

The approved pricing pipeline is:

1. Resolve effective store product price.
2. Add accepted customization prices.
3. Multiply by quantity.
4. Calculate order subtotal.
5. Evaluate and apply promotion rules.
6. Calculate tax using the configured tax policy.
7. Apply explicit rounding.
8. Produce total.
9. Capture all values as order snapshots.

The legal/business tax inclusion policy must be configured explicitly before production.

## Rounding

- Java calculations use explicit scale and rounding mode.
- No operation relies on implicit `BigDecimal` rounding.
- Default accounting rounding is `HALF_UP` unless Finance approves another rule.
- Cash rounding, if required, is stored separately as `rounding_amount`.
- Stored totals must satisfy the order equation after rounding.
- Refund allocation must not exceed captured payment or original line allocation.

## Cash and Change

For cash payment, store:

```text
settlement_currency
tender_currency
exchange_rate
amount_due
amount_received
change_amount
```

The system calculates change server-side. The exchange rate used for the payment is immutable after successful processing.

## Alternatives Considered

### Java `double` and PostgreSQL floating point

Rejected because financial precision and equality behavior are unsafe.

### Integer minor units only

Considered valid, but not selected initially because KHR/USD configuration, cost precision, tax rates, and exchange calculations benefit from explicit decimal scale. It may be reconsidered for provider-specific API adapters.

### Currencyless decimal columns

Rejected because amounts become ambiguous and cross-currency errors become likely.

## Consequences

### Positive

- Exact, auditable arithmetic.
- Clear multi-currency behavior.
- Consistent order, payment, and refund snapshots.
- Safe server-authoritative pricing.

### Negative

- Developers must use precise comparison and rounding APIs.
- Currency conversion requires explicit policies.
- Database and Java scales require careful mapping.
- Tax rules require business approval.

## Validation Rules

- Price and cost are non-negative.
- Quantity is positive.
- Discount never exceeds the eligible amount unless an explicit policy allows it.
- Refund total never exceeds captured amount minus prior completed refunds.
- `amount_paid` and `amount_refunded` are non-negative.
- Exchange rate is positive.
- Currency codes are validated against supported configuration.

## Revisit Conditions

Revisit representation if provider APIs require integer minor units end-to-end, if additional currencies have different scale rules, or if Finance approves a different tax and rounding policy. Floating point remains prohibited.
