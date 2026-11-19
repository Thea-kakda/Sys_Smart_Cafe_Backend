-- ============================================================
-- V053: Promotion checkout reservation, settlement and expiry (Phase B2)
-- Database: PostgreSQL
--
-- Order/redemption promotion snapshots so receipts and history never depend on
-- the live definition of a campaign, plus the fields the atomic reservation
-- lifecycle needs (eligible subtotal, definition snapshot, release reason and
-- request metadata). Historical rows stay NULL: no backfill is invented.
-- ============================================================

ALTER TABLE ordering.orders
    ADD COLUMN promotion_id uuid REFERENCES promotion.promotions (promotion_id),
    ADD COLUMN promotion_code_snapshot varchar(50),
    ADD COLUMN promotion_name_snapshot varchar(150),
    ADD COLUMN promotion_type_snapshot varchar(30),
    ADD COLUMN promotion_value_snapshot numeric(14, 4),
    ADD COLUMN promotion_eligible_subtotal numeric(14, 4),
    ADD COLUMN promotion_discount_amount numeric(14, 4),
    ADD COLUMN promotion_version bigint,
    ADD CONSTRAINT ck_order_promotion_subtotal CHECK (
        promotion_eligible_subtotal IS NULL
        OR promotion_eligible_subtotal >= 0
    ),
    ADD CONSTRAINT ck_order_promotion_discount CHECK (
        promotion_discount_amount IS NULL
        OR promotion_discount_amount >= 0
    );

ALTER TABLE promotion.promotion_redemptions
    ADD COLUMN eligible_subtotal numeric(14, 4),
    ADD COLUMN promotion_code_snapshot varchar(50),
    ADD COLUMN promotion_name_snapshot varchar(150),
    ADD COLUMN promotion_type_snapshot varchar(30),
    ADD COLUMN promotion_value_snapshot numeric(14, 4),
    ADD COLUMN release_reason varchar(60),
    ADD COLUMN idempotency_key varchar(100),
    ADD COLUMN correlation_id varchar(100),
    ADD CONSTRAINT ck_redemption_eligible_subtotal CHECK (
        eligible_subtotal IS NULL
        OR eligible_subtotal >= 0
    );

-- One Promotion per Order: an order owns at most one redemption row, whatever
-- the promotion. The lifecycle transitions that row instead of inserting a
-- second one, so this uniqueness also makes retries structurally unable to
-- reserve twice.
CREATE UNIQUE INDEX ux_redemption_order
    ON promotion.promotion_redemptions (order_id);
