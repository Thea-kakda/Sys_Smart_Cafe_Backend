-- =============================================================================
-- V014: Schema type reconciliation - CHAR -> VARCHAR (Checkpoint 2C, Option B)
-- =============================================================================
-- Purpose:
-- Hibernate schema validation (test profile, ddl-auto=validate) fails because
-- several physical columns were created as PostgreSQL CHAR/bpchar while every
-- corresponding JPA entity maps the column as String (VARCHAR). This migration
-- aligns the physical type with the code-wide VARCHAR convention instead of
-- weakening validation or editing applied migrations.
--
-- Evidence for Option B (convert to VARCHAR) over Option A (keep CHAR):
--   * Every entity mapping for these columns is String -> VARCHAR
--     (Store, Product, Cart, Order, Payment, Promotion, PromotionRedemption,
--     AuthSession, DeviceToken). There is no CHAR-mapped entity.
--   * The physical DDL already contradicts the logical DBML twice:
--     cart.carts.currency_code is char(30) while DBML says char(3), and
--     identity.auth_sessions.refresh_token_hash is CHAR(64) while DBML says
--     varchar(128). The CHAR usage in migrations was an error, not a policy.
--   * ADR-005 requires a "three-character ISO-style currency code"; it does
--     not mandate the bpchar storage type. ISO 4217 codes and fixed-length
--     hex token hashes are stored without trailing-space ambiguity in
--     VARCHAR, avoiding known bpchar blank-padding comparison hazards.
--   * Existing CHECK constraints (e.g. currency IN ('USD','KHR')) are
--     unaffected by the type change.
--
-- Data safety:
--   * All affected columns hold fixed-width values (3-char codes, 64-char
--     hex hashes) or were blank-padded bpchar values; trailing spaces are
--     trimmed explicitly during conversion. No row is widened, narrowed
--     beyond padding, or deleted. No applied migration is modified.
-- =============================================================================

-- Currency columns: bpchar -> varchar(3) (trimmed to remove bpchar padding).
ALTER TABLE organization.stores          ALTER COLUMN default_currency TYPE varchar(3) USING trim(default_currency);
ALTER TABLE catalog.products             ALTER COLUMN currency_code    TYPE varchar(3) USING trim(currency_code);
ALTER TABLE cart.carts                   ALTER COLUMN currency_code    TYPE varchar(3) USING trim(currency_code);
ALTER TABLE ordering.orders              ALTER COLUMN currency_code    TYPE varchar(3) USING trim(currency_code);
ALTER TABLE payment.payments             ALTER COLUMN currency_code    TYPE varchar(3) USING trim(currency_code);
ALTER TABLE promotion.promotions         ALTER COLUMN currency_code    TYPE varchar(3) USING trim(currency_code);
ALTER TABLE promotion.promotion_redemptions ALTER COLUMN currency_code TYPE varchar(3) USING trim(currency_code);

-- Token hash columns: CHAR(64) -> varchar(64) (SHA-256 hex; matches entity mapping).
ALTER TABLE identity.auth_sessions       ALTER COLUMN refresh_token_hash TYPE varchar(64) USING trim(refresh_token_hash);
ALTER TABLE notification.device_tokens   ALTER COLUMN token_hash         TYPE varchar(64) USING trim(token_hash);
