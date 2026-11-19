-- ============================================================
-- V021: Payment currency-conversion snapshot (Checkpoint 5D)
-- Additive columns on payment.payments sufficient to reconstruct
-- the financial transaction independently of the live rate table.
-- These values are copied once at settlement and are PERMANENT:
-- editing/deleting a currency_exchange_rates row later must never
-- change an old payment, receipt, change, audit or replay. There is
-- intentionally NO foreign key to payment.currency_exchange_rates.
-- All columns are nullable so existing (KHQR / pre-5D) rows are valid.
-- ============================================================

ALTER TABLE payment.payments
    ADD COLUMN tender_currency           varchar(3),
    ADD COLUMN tender_amount_received    numeric(14, 4),
    ADD COLUMN exchange_rate_id          uuid,
    ADD COLUMN applied_exchange_rate     numeric(20, 10),
    ADD COLUMN rate_base_currency        varchar(3),
    ADD COLUMN rate_quote_currency       varchar(3),
    ADD COLUMN exchange_rate_inverted    boolean,
    ADD COLUMN settlement_amount_received numeric(14, 4),
    ADD COLUMN settlement_change_amount  numeric(14, 4),
    ADD COLUMN tender_change_amount      numeric(14, 4),
    ADD COLUMN change_currency           varchar(3),
    ADD COLUMN rounding_amount           numeric(14, 4);

-- Non-negative money invariants (nullable-safe: NULL passes the check).
ALTER TABLE payment.payments
    ADD CONSTRAINT ck_payment_tender_amount CHECK (tender_amount_received IS NULL OR tender_amount_received >= 0),
    ADD CONSTRAINT ck_payment_settlement_amount CHECK (settlement_amount_received IS NULL OR settlement_amount_received >= 0),
    ADD CONSTRAINT ck_payment_settlement_change CHECK (settlement_change_amount IS NULL OR settlement_change_amount >= 0),
    ADD CONSTRAINT ck_payment_tender_change CHECK (tender_change_amount IS NULL OR tender_change_amount >= 0),
    ADD CONSTRAINT ck_payment_applied_rate CHECK (applied_exchange_rate IS NULL OR applied_exchange_rate > 0);

-- A snapshot, when present, must name its tender and settlement currencies.
ALTER TABLE payment.payments
    ADD CONSTRAINT ck_payment_snapshot_coherent CHECK (
        tender_currency IS NULL
        OR (change_currency IS NOT NULL AND settlement_amount_received IS NOT NULL)
    );
