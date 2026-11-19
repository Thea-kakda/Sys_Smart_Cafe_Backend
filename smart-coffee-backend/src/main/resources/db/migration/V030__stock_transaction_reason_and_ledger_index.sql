-- =============================================================================
-- V030: Stock adjustment reason + movement ledger read index
-- =============================================================================
-- Supports the production-facing inventory write contract:
--   POST /api/v1/stores/{storeId}/inventory/adjustments
--   GET  /api/v1/stores/{storeId}/inventory/movements
--
-- stock_transactions existed since V005 but only ever carried reference
-- pointers (ORDER / RESERVATION). A manual adjustment is operator-initiated and
-- must explain itself on the ledger row, so a nullable reason column is added
-- (STOCK_IN / ADJUSTMENT / RETURN / DAMAGED writes set it; system-generated
-- SALE_CONSUMPTION rows keep it null). Nullable + IF NOT EXISTS keeps the
-- migration idempotent and does not touch any existing row.
--
-- The descending (store_id, occurred_at) index backs the bounded, paginated
-- ledger read, which always filters by store and orders newest-first.
-- =============================================================================

ALTER TABLE inventory.stock_transactions
    ADD COLUMN IF NOT EXISTS reason varchar(500);

CREATE INDEX IF NOT EXISTS ix_stock_transactions_store_occurred
    ON inventory.stock_transactions (store_id, occurred_at DESC);
