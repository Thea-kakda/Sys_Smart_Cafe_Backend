-- =============================================================================
-- V019: Currency exchange rate table (Checkpoint 5A — audited P0 model)
-- =============================================================================
-- payment.payments and catalog products are single-currency, but cash payments
-- are routinely tendered in a second currency (e.g. KHR tendered against a USD
-- order). Until now the ONLY rate in the system was a client-supplied
-- CashPaymentRequest.exchangeRate that directly controlled settlement — a P0
-- financial-integrity defect corrected in Checkpoint 5C. This table is the
-- authoritative rate source the backend selects from.
--
-- Currency columns use varchar(3), the reconciled type chosen in V014 (all
-- entities map String -> VARCHAR; ADR-005 mandates a three-character ISO-style
-- code, not a fixed-width CHAR).
--
-- Rate direction (documented, single meaning everywhere):
--     1 base_currency = rate quote_currency
-- e.g. USD -> KHR rate = 4100.0000000000
--
-- Seed policy: NO operational rate is inserted here. A migration-seeded rate
-- would imply a maintenance process that does not exist; rates are created via
-- the authenticated management API (Checkpoint 10) so provenance
-- (created_by_user_id, source, effective window) is always recorded. Do not
-- treat any stored rate as a live market feed.
-- =============================================================================

CREATE TABLE payment.currency_exchange_rates (
    exchange_rate_id uuid PRIMARY KEY,
    -- NULL store_id = GLOBAL rate; a non-null store overrides global (5B).
    store_id uuid REFERENCES organization.stores (store_id),
    base_currency varchar(3) NOT NULL,
    quote_currency varchar(3) NOT NULL,
    rate numeric(20, 10) NOT NULL,
    -- Free-text provenance: MANUAL, FEED:<provider>, IMPORT, etc.
    source varchar(40) NOT NULL,
    effective_from timestamptz NOT NULL,
    -- NULL = open-ended (currently in force until superseded).
    effective_to timestamptz,
    active boolean NOT NULL DEFAULT true,
    created_by_user_id uuid REFERENCES identity.users (user_id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_xrate_currency_pair CHECK (base_currency <> quote_currency),
    CONSTRAINT ck_xrate_positive CHECK (rate > 0),
    -- Supported currency allowlist (Cambodian cafe: USD settlement + KHR cash).
    CONSTRAINT ck_xrate_base_currency CHECK (base_currency IN ('USD', 'KHR')),
    CONSTRAINT ck_xrate_quote_currency CHECK (quote_currency IN ('USD', 'KHR')),
    CONSTRAINT ck_xrate_period CHECK (
        effective_to IS NULL
        OR effective_to > effective_from
    )
);

-- Deterministic selection index: for a moment, currency pair, prefer the
-- branch-specific row, then the latest effective window. The service also
-- orders by these columns.
CREATE INDEX ix_xrate_lookup ON payment.currency_exchange_rates (
    base_currency,
    quote_currency,
    store_id,
    effective_from DESC
);

CREATE INDEX ix_xrate_active ON payment.currency_exchange_rates (active)
WHERE active = true;
